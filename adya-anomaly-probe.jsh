#!/usr/bin/env -S bash -c 'exec jshell <(tail -n +2 "$0")'
// adya-anomaly-probe.jsh
//
// Tries to provoke each of Atul Adya's isolation anomalies against a running copy of the cinema
// booking app, through its public HTTP API only, and reports that none of them occurs. The guide
// -- what each probe does, why the anomaly cannot happen here, and which test in the repository
// makes it happen once the protection is taken away -- is served beside this file:
//
//     http://183.81.158.231:8080/adya-anomaly-probe.html
//
// WHY THE FIRST LINE LOOKS LIKE THAT. jshell does not skip a "#!" line: it reports "illegal
// character: '#'" and carries on. So the shebang starts bash, and bash hands jshell this file
// minus its first line. Run it as ./adya-anomaly-probe.jsh after chmod +x. (Needs JDK 17+ and
// GNU env; `jshell adya-anomaly-probe.jsh` works too, after printing that one error.)
//
// SETTINGS ARE ENVIRONMENT VARIABLES, because jshell passes no arguments to a script -- and a
// session cookie on a command line is readable by every user of the machine through `ps`.
//
//   ADYA_SHOWING    required  seat-map URL of an on-sale showing at least an hour away, ideally a
//                             fresh, empty one, e.g.
//                             https://www.tiketbioskop.xyz:8443/halls/<hall>/showings/<showing>
//   ADYA_COOKIE     required  the Cookie header of a browser signed in as a customer -- at least
//                             q_session=... (and any q_session_chunk_N=... beside it)
//   ADYA_SHOWING_2  optional  a second showing on the SAME DAY, so G2 races two showings
//   ADYA_COOKIE_2   optional  a second customer account, so the races are between two people
//   ADYA_ONLY       optional  run a subset, e.g. ADYA_ONLY=G0,G2-item
//   ADYA_ROUNDS     optional  rounds per race (default 3, at most 10)
//   ADYA_DRY_RUN    optional  1 = read-only: check the showing and the cookie, print the plan
//   ADYA_NAME, ADYA_PHONE     contact details sent with each hold; the app only uses them for
//                             an account that has never booked before
//
// WHAT IT LEAVES BEHIND. Every hold it makes, it cancels again, and the showing ends as empty as
// it started. The account's order history keeps the cancelled and failed orders, and the
// tenant's audit log keeps a row for each change -- both are append-only by design. If a run is
// interrupted, its holds expire by themselves within 10 minutes; a CONFIRMED seat does not, so
// cancel anything left over on /orders.
//
// Exit status: 0 no anomaly observed, 1 an anomaly was observed, 2 could not run or inconclusive.

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class Adya {

    // ------------------------------------------------------------------------ knobs

    static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    static final String UA =
            "adya-anomaly-probe/1 (jshell; +http://183.81.158.231:8080/adya-anomaly-probe.html)";
    /** Anonymous viewers polling the seat map while a probe writes, each pausing between reads:
     * roughly what sixty open seat-map tabs cost, and only for a few seconds at a time. Kept
     * gentle on purpose -- the office server shares its CPU with another production application
     * (deploy/loadtest/README.md is why no load test may target it), and this is a correctness
     * probe, not a load test. */
    static final int POLLERS = 3;
    static final long POLL_PAUSE_MS = 100;
    /** Simultaneous holds per G2-item round: the number SeatBookingConcurrencyTest races. */
    static final int RACERS = 8;

    // ------------------------------------------------------------------------ run state

    static String base;
    static String url1, url2;
    static Account c1, c2;
    static Showing s1, s2;
    static int rounds;
    static Set<String> only;
    static String name, phone;
    /** Every order this run created and who owns it, so the closing sweep can cancel whatever is
     * still live even after a probe died half-way. */
    static final Map<String, Account> ledger = new ConcurrentHashMap<>();
    static final List<Result> results = new ArrayList<>();
    static final HttpClient MAIN = client();
    static final List<HttpClient> LANES = new ArrayList<>();
    static final List<HttpClient> VIEWERS = new ArrayList<>();

    // Seat plan, fixed at preflight so every probe works on seats nobody else in the run touches.
    static List<Seat> anchor, g1cSeats, g2itemSeat, g0Seats, g1bSeats, gsingleSeats, cursorSeats,
            g2SeatsA, g2SeatsB;
    static String anchorGroup;
    static volatile boolean wrote;
    /** Counted so the summary can say exactly what the run cost the server. */
    static final AtomicInteger POSTS = new AtomicInteger(), GETS = new AtomicInteger();
    static final long STARTED = System.nanoTime();

    record Account(String name, String cookie) {}

    record Resp(int status, String body, HttpHeaders headers, long sent, long done) {
        String header(String n) { return headers.firstValue(n).orElse(null); }
    }

    /** One seat as the grid renders it. {@code id} is only in the markup of an available seat. */
    record Seat(String label, String status, String id) {}

    record Hold(Account who, String seats, boolean ok, String group, String refusal, long sent,
                long done) {
        boolean taken() { return refusal != null && refusal.contains("just taken"); }
        boolean dayCap() { return refusal != null && refusal.contains(" a day."); }
        String brief() {
            if (ok) {
                return "held (order " + group.substring(0, 8) + ")";
            }
            if (taken()) {
                return "refused, just taken";
            }
            if (dayCap()) {
                return "refused by the daily cap";
            }
            return "refused: " + (refusal.length() > 70 ? refusal.substring(0, 67) + "..." : refusal);
        }
    }

    record Line(String seat, String status, String reservationId) {}

    record Order(String group, String status, List<Line> lines) {
        Line line(String seat) {
            return lines.stream().filter(l -> l.seat().equals(seat)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("order " + group + " has no seat " + seat));
        }
    }

    enum Verdict { NOT_OBSERVED, OBSERVED, INCONCLUSIVE, SKIPPED }

    record Result(String code, String name, Verdict verdict, String line) {}

    /** A reason the run cannot go on. Printed as-is, and the run stops. */
    static final class Stop extends RuntimeException {
        Stop(String m) { super(m); }
    }

    static final class Showing {
        String pool, session, title;
        ZonedDateTime startsAt;
        List<Seat> seats;
        int perHold, perDay, firstBuyerMin;
        boolean ageGate;

        String path() { return "/halls/" + pool + "/showings/" + session; }
        String fragment() { return path() + "/fragment"; }
        String when() { return startsAt == null ? "date unread" : startsAt.toLocalDateTime().toString().replace('T', ' ') + " WIB"; }
    }

    // ------------------------------------------------------------------------ entry point

    static int run() {
        try {
            configure();
            preflight();
            if ("1".equals(env("ADYA_DRY_RUN"))) {
                System.out.println("\nADYA_DRY_RUN=1: stopping before the first write.");
                return 0;
            }
            probes();
        } catch (Stop s) {
            System.out.println("\nCANNOT GO ON: " + s.getMessage());
            sweep();
            return summary(2);
        } catch (Throwable t) {
            System.out.println("\nUNEXPECTED ERROR: " + t);
            t.printStackTrace(System.out);
            sweep();
            return summary(2);
        }
        sweep();
        return summary(0);
    }

    // ------------------------------------------------------------------------ configuration

    static final Pattern SHOWING_URL = Pattern.compile(
            "^(https?://[^/]+)(?:/id)?/halls/([0-9a-fA-F-]{36})/showings/([0-9a-fA-F-]{36})");

    static void configure() {
        url1 = env("ADYA_SHOWING");
        if (url1 == null) {
            throw new Stop("set ADYA_SHOWING to the seat-map URL of an empty showing, and ADYA_COOKIE"
                    + " to a signed-in customer's cookie. The guide walks through both:\n"
                    + "    http://183.81.158.231:8080/adya-anomaly-probe.html");
        }
        Matcher m = SHOWING_URL.matcher(url1);
        if (!m.find()) {
            throw new Stop("ADYA_SHOWING should look like https://host:8443/halls/<id>/showings/<id>, got " + url1);
        }
        base = m.group(1);
        url2 = env("ADYA_SHOWING_2");
        if (env("ADYA_COOKIE") == null) {
            throw new Stop("set ADYA_COOKIE to the Cookie header of a browser signed in as a customer"
                    + " (at least its q_session cookie).");
        }
        c1 = new Account("customer 1", cookie(env("ADYA_COOKIE")));
        c2 = env("ADYA_COOKIE_2") == null ? null : new Account("customer 2", cookie(env("ADYA_COOKIE_2")));
        rounds = Math.max(1, Math.min(10, Integer.parseInt(Optional.ofNullable(env("ADYA_ROUNDS")).orElse("3"))));
        only = env("ADYA_ONLY") == null ? null : Arrays.stream(env("ADYA_ONLY").split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        name = Optional.ofNullable(env("ADYA_NAME")).orElse("Adya probe");
        phone = Optional.ofNullable(env("ADYA_PHONE")).orElse("+620000000000");
    }

    /** Accepts what DevTools shows -- with or without the "Cookie:" prefix -- or a bare q_session
     * value. booking_lang is dropped: refusals are recognised by their English wording, and an
     * Indonesian cookie would otherwise make every hold answer in Indonesian. */
    static String cookie(String raw) {
        String c = raw.strip();
        if (c.regionMatches(true, 0, "cookie:", 0, 7)) {
            c = c.substring(7).strip();
        }
        if (!c.matches("^[A-Za-z0-9_.-]+=.*")) {
            c = "q_session=" + c;
        }
        return Arrays.stream(c.split(";")).map(String::strip).filter(p -> !p.isEmpty())
                .filter(p -> !p.startsWith("booking_lang=")).collect(Collectors.joining("; "));
    }

    static boolean wants(String code) {
        if (only == null) {
            return true;
        }
        String c = code.toUpperCase(Locale.ROOT);
        return only.contains(c) || (c.equals("G-CURSOR") && (only.contains("P4") || only.contains("LOST-UPDATE")));
    }

    // ------------------------------------------------------------------------ preflight

    static void preflight() {
        System.out.println("Adya anomaly probe -> " + base);
        System.out.println("-".repeat(78));
        s1 = readShowing(url1);
        describe("Showing  ", s1);
        if (url2 != null) {
            Showing other = readShowing(url2);
            if (other.session.equals(s1.session)) {
                System.out.println("Showing 2 is the same showing as ADYA_SHOWING; ignoring it.");
            } else {
                s2 = other;
                describe("Showing 2", s2);
            }
        }
        checkAccount(c1);
        if (c2 != null) {
            checkAccount(c2);
        } else {
            System.out.println("Accounts  customer 2 not given: customer 1 plays both parts in every race.");
        }

        if (s1.ageGate) {
            throw new Stop("this showing's film carries an age rating, so every hold would record an age"
                    + " attestation for your account. Create a showing with a label and no film.");
        }
        if (s1.startsAt != null && s1.startsAt.isBefore(ZonedDateTime.now(WIB).plusHours(1))) {
            throw new Stop("the showing starts at " + s1.when() + ". Use one at least an hour away: the"
                    + " viability sweep cancels an under-sold showing 15 minutes after its start.");
        }
        if (s2 != null && s2.ageGate) {
            System.out.println("Showing 2 has an age-rated film; G2 will use showing 1 only.");
            s2 = null;
        }
        long taken = s1.seats.stream().filter(s -> !s.status().equals("available")).count();
        if (taken > 0) {
            System.out.println("Note      " + taken + " seat(s) already taken on showing 1 by someone else;"
                    + " the probes use only free seats.");
        }
        plan();
    }

    static final Pattern H1 = Pattern.compile("<h1>([^<]*)</h1>");
    static final Pattern WHEN = Pattern.compile(
            "(\\d{1,2}) ([A-Za-z]{3,4})\\.? (\\d{4}), (\\d{2}):(\\d{2}) WIB");
    static final Pattern PER_HOLD = Pattern.compile("Up to (\\d+) seats per booking");
    static final Pattern PER_DAY = Pattern.compile("Up to (\\d+) seats a day");
    static final Pattern FIRST_BUYER = Pattern.compile("must be for at least (\\d+) seats");
    static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("mei", 5), Map.entry("jun", 6), Map.entry("jul", 7),
            Map.entry("aug", 8), Map.entry("agu", 8), Map.entry("sep", 9), Map.entry("oct", 10),
            Map.entry("okt", 10), Map.entry("nov", 11), Map.entry("dec", 12), Map.entry("des", 12));

    static Showing readShowing(String url) {
        Matcher m = SHOWING_URL.matcher(url);
        if (!m.find()) {
            throw new Stop("not a seat-map URL: " + url);
        }
        if (!m.group(1).equals(base)) {
            throw new Stop("both showings must be on the same site: " + url);
        }
        Showing s = new Showing();
        s.pool = m.group(2).toLowerCase(Locale.ROOT);
        s.session = m.group(3).toLowerCase(Locale.ROOT);
        Resp r = get(MAIN, null, s.path());
        if (r.status() != 200) {
            throw new Stop("GET " + base + s.path() + " answered HTTP " + r.status());
        }
        String main = r.body().substring(Math.max(0, r.body().indexOf("<main")));
        s.title = unescape(Optional.ofNullable(first(H1, main)).orElse("(untitled)")).strip();
        s.startsAt = parseWhen(main);
        s.seats = seats(r.body());
        if (s.seats.isEmpty()) {
            throw new Stop("found no seat grid at " + url + " -- has the seat-map markup changed?");
        }
        s.perHold = intOr(first(PER_HOLD, main), 0);
        s.perDay = intOr(first(PER_DAY, main), 0);
        s.firstBuyerMin = intOr(first(FIRST_BUYER, main), 1);
        s.ageGate = r.body().contains("name=\"ageAttested\"");
        return s;
    }

    static ZonedDateTime parseWhen(String html) {
        Matcher m = WHEN.matcher(html);
        if (!m.find()) {
            return null;
        }
        Integer month = MONTHS.get(m.group(2).substring(0, 3).toLowerCase(Locale.ROOT));
        return month == null ? null : ZonedDateTime.of(Integer.parseInt(m.group(3)), month,
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                0, 0, WIB);
    }

    static void describe(String label, Showing s) {
        long free = s.seats.stream().filter(x -> x.status().equals("available")).count();
        System.out.println(label + " " + s.title + " | " + s.when() + " | " + s.seats.size() + " seats, "
                + (free == s.seats.size() ? "all free" : free + " free"));
        System.out.println("          rules: " + (s.perHold > 0 ? s.perHold + " seats per booking" : "no per-booking cap")
                + ", " + (s.perDay > 0 ? s.perDay + " per customer per day" : "no daily cap")
                + ", " + (s.firstBuyerMin > 1 ? "first booking at least " + s.firstBuyerMin + " seats" : "no first-booking minimum"));
    }

    static void checkAccount(Account a) {
        Resp r = get(MAIN, a, "/orders");
        if (r.status() == 200) {
            System.out.println("Accounts  " + a.name() + ": signed in (GET /orders -> 200)");
            return;
        }
        if (r.status() / 100 == 3) {
            String to = Optional.ofNullable(r.header("Location")).map(l -> URI.create(l).getHost()).orElse("?");
            throw new Stop(a.name() + "'s cookie is not a live session: /orders redirected to sign-in at "
                    + to + ".\n    Sign in again in the browser and copy a fresh cookie -- a Google"
                    + " sign-in session here lasts about an hour.");
        }
        if (r.status() == 403) {
            throw new Stop(a.name() + " is signed in but may not book seats (HTTP 403). A platform-admin"
                    + " account cannot hold seats; sign in with an ordinary Google account.");
        }
        throw new Stop(a.name() + ": GET /orders answered HTTP " + r.status() + " -- not signed in?");
    }

    /** Hands every probe its own seats, so a leftover from one can never be read as the result of
     * another, and the final seat map shows at a glance that everything was given back. */
    static void plan() {
        Deque<Seat> free = s1.seats.stream().filter(s -> s.status().equals("available"))
                .collect(Collectors.toCollection(ArrayDeque::new));
        anchor = take(free, anchorCount());
        g0Seats = take(free, 2);
        g1cSeats = take(free, 2);
        g2itemSeat = take(free, 1);
        g1bSeats = take(free, g1bSize());
        gsingleSeats = take(free, 2);
        cursorSeats = take(free, 2);
        int g2 = g2Size();
        g2SeatsA = g2 > 0 ? take(free, g2) : List.of();
        if (g2 > 0 && s2 == null) {
            g2SeatsB = take(free, g2);
        } else if (g2 > 0) {
            // Two showings of one hall that overlap in time would share seats under the EXCLUDE
            // constraint (it is per seat and per time range, not per showing), so showing 2 skips
            // every seat already planned on showing 1.
            Set<String> used = new HashSet<>();
            for (List<Seat> l : List.of(anchor, g0Seats, g1cSeats, g2itemSeat, g1bSeats, gsingleSeats,
                    cursorSeats, g2SeatsA)) {
                l.forEach(x -> used.add(x.id()));
            }
            Deque<Seat> free2 = s2.seats.stream().filter(s -> s.status().equals("available"))
                    .filter(s -> !used.contains(s.id())).collect(Collectors.toCollection(ArrayDeque::new));
            g2SeatsB = take(free2, g2);
        } else {
            g2SeatsB = List.of();
        }
        System.out.println("Seats     " + names(anchor) + " anchor | " + names(g0Seats) + " G0 | "
                + names(g1bSeats) + " G1b | " + names(g1cSeats) + " G1c | " + names(cursorSeats) + " G-cursor");
        System.out.println("          " + names(gsingleSeats) + " G-single | " + names(g2itemSeat)
                + " G2-item | " + (g2 > 0 ? names(g2SeatsA) + " + " + names(g2SeatsB)
                + (s2 == null ? "" : " (showing 2)") + " G2" : "G2 not applicable"));
        System.out.println("-".repeat(78));
    }

    static List<Seat> take(Deque<Seat> free, int n) {
        if (free.size() < n) {
            throw new Stop("the hall is too small for the plan: " + n + " more free seat(s) needed."
                    + " Use a hall of about 30 seats or more.");
        }
        List<Seat> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(free.removeFirst());
        }
        return out;
    }

    /** G1b's doomed hold is k free seats plus one that is already taken. k is kept inside both
     * caps with the anchor's two seats counted, so the hold is refused by the seat that is taken
     * and never by a cap -- a cap refusal happens before any seat is written, which would make the
     * probe vacuous. */
    static int g1bSize() {
        int k = 4;
        if (s1.perHold > 0) {
            k = Math.min(k, s1.perHold - 1);
        }
        if (s1.perDay > 0) {
            k = Math.min(k, s1.perDay - anchorCount() - 1);
        }
        return Math.max(0, k);
    }

    /** The anchor is held from the first probe to the last so the showing is never empty: on an
     * empty showing the first-booking rule refuses a one-seat hold before it reaches any seat,
     * and G1a and G2-item race one-seat holds. Two seats, or the rule's minimum if it is higher. */
    static int anchorCount() { return Math.max(2, s1.firstBuyerMin); }

    /** G2's hold size s: one hold fits under the daily cap with the anchor counted, two do not. */
    static int g2Size() {
        int cap = s1.perDay;
        if (cap <= 0) {
            return 0;
        }
        int s = (cap - anchorCount()) / 2 + 1;
        boolean fitsOne = anchorCount() + s <= cap;
        boolean breaksWithTwo = anchorCount() + 2 * s > cap;
        boolean underHoldCap = s1.perHold <= 0 || s <= s1.perHold;
        // Showing 2 starts empty, so its hold is a first booking and must meet the rule's minimum.
        boolean opensShowing2 = s2 == null || s >= s2.firstBuyerMin;
        return fitsOne && breaksWithTwo && underHoldCap && opensShowing2 && s >= 1 ? s : 0;
    }

    // ------------------------------------------------------------------------ the probes

    static void probes() {
        List<String> all = List.of("G0", "G1a", "G1b", "G1c", "G-cursor", "G-single", "G2-item", "G2");
        if (all.stream().noneMatch(Adya::wants)) {
            throw new Stop("ADYA_ONLY names no probe; use any of " + String.join(",", all));
        }
        heading("anchor", "seats held from the first probe to the last",
                "the showing is never empty, so the first-booking rule never refuses a one-seat hold");
        Hold h = hold(MAIN, c1, s1, anchor);
        if (!h.ok()) {
            fatal(h, false);
            throw new Stop("could not hold the anchor seats " + names(anchor) + ": " + h.refusal());
        }
        anchorGroup = h.group();
        say(names(anchor) + " " + h.brief());
        // Adya's order, weakest isolation level first: PL-1, PL-2, PL-CS, PL-2+, PL-2.99, PL-3.
        if (wants("G0")) {
            record(g0());
        }
        if (wants("G1a")) {
            record(g1a());
        }
        if (wants("G1b")) {
            record(g1b());
        }
        if (wants("G1c")) {
            record(g1c());
        }
        if (wants("G-cursor")) {
            record(gCursor());
        }
        if (wants("G-single")) {
            record(gSingle());
        }
        if (wants("G2-item")) {
            record(g2item());
        }
        if (wants("G2")) {
            record(g2());
        }
        release(c1, anchorGroup);
        System.out.println();
        System.out.println("[anchor] " + names(anchor) + " given back.");
    }

    /** One opposite-order race over a pair of seats: who won, what the winner's order holds, and
     * what the seat map showed straight afterwards. G0 and G1c read the same race for the two
     * different ways it could go wrong. */
    record PairRound(List<Hold> holds, List<Hold> won, Order winner, Map<String, List<String>> map) {}

    /** Holds of a+b and b+a released at the same instant, alternating accounts. The opposite order
     * is the point: each hold writes its first seat, then goes for the seat the other wrote first. */
    static PairRound pairRace(Seat a, Seat b, int lanes) {
        List<Function<HttpClient, Hold>> jobs = new ArrayList<>();
        for (int i = 0; i < lanes; i++) {
            Account who = (i % 2 == 1 && c2 != null) ? c2 : c1;
            List<Seat> pair = i % 2 == 0 ? List.of(a, b) : List.of(b, a);
            jobs.add(http -> hold(http, who, s1, pair));
        }
        List<Hold> hs = together(jobs);
        hs.forEach(h -> fatal(h, false));
        List<Hold> won = hs.stream().filter(Hold::ok).toList();
        Order winner = won.size() == 1 ? order(won.get(0).who(), won.get(0).group()) : null;
        Map<String, List<String>> map = byLabel(readSeats(s1));
        for (Hold h : won) {
            release(h.who(), h.group());
        }
        return new PairRound(hs, won, winner, map);
    }

    static String describe(PairRound p) {
        long taken = p.holds().stream().filter(Hold::taken).count();
        String owner = p.winner() == null ? "" : "; order " + p.winner().group().substring(0, 8) + " has "
                + p.winner().lines().stream().map(l -> l.seat() + " " + l.status()).collect(Collectors.joining(", "));
        return p.won().size() + " of " + p.holds().size() + " succeeded, " + taken + " refused, just taken" + owner;
    }

    /** G0: a cycle of write-write edges -- two writers' changes interleaved into one state. */
    static Result g0() {
        String code = "G0", title = "Write cycle (dirty write)";
        heading(code, "write cycle", "two couples book the same pair and each goes home with one seat of it");
        Seat a = g0Seats.get(0), b = g0Seats.get(1);
        int bad = 0, odd = 0;
        for (int r = 1; r <= rounds; r++) {
            PairRound p = pairRace(a, b, 4);
            boolean whole = p.winner() != null && p.winner().lines().size() == 2
                    && p.winner().lines().stream().allMatch(l -> l.status().equals("held"));
            boolean shownOnce = p.map().getOrDefault(a.label(), List.of()).equals(List.of("held"))
                    && p.map().getOrDefault(b.label(), List.of()).equals(List.of("held"));
            say("round " + r + ": " + a.label() + "+" + b.label() + " and " + b.label() + "+" + a.label()
                    + ", twice each, at once -> " + describe(p));
            if (p.won().size() > 1 || (p.won().size() == 1 && !(whole && shownOnce))) {
                bad++;
            } else if (p.won().isEmpty() || p.holds().stream().anyMatch(x -> !x.ok() && !x.taken())) {
                odd++;
            }
        }
        why("A claim on a seat is a write, and PostgreSQL never lets two transactions hold uncommitted"
                + " writes to the same thing: the second waits for the first to commit or roll back."
                + " A hold is also all-or-nothing -- one that loses a seat withdraws the seats it had"
                + " already written before it commits -- and every hold on a showing queues on that"
                + " showing's row lock (FOR UPDATE) first. G0 is ruled out at every isolation level;"
                + " there is no weaker setting that would show it.");
        if (bad > 0) {
            return verdict(code, title, Verdict.OBSERVED, "the pair ended split between orders, or held"
                    + " twice, in " + bad + " round(s)");
        }
        if (odd > 0) {
            return verdict(code, title, Verdict.INCONCLUSIVE, odd + " round(s) had no winner or an"
                    + " unexpected refusal");
        }
        return verdict(code, title, Verdict.NOT_OBSERVED, "in all " + rounds + " rounds one order got"
                + " both seats and the other three got neither");
    }

    /** G1c: a cycle made only of write-read edges -- each transaction read what the other wrote. */
    static Result g1c() {
        String code = "G1c", title = "Circular information flow";
        heading(code, "circular information flow", "two couples reach for one pair from opposite ends;"
                + " each sees the other's half-made booking, both give up, and the pair goes unsold");
        Seat a = g1cSeats.get(0), b = g1cSeats.get(1);
        int nobody = 0, twice = 0;
        for (int r = 1; r <= rounds; r++) {
            PairRound p = pairRace(a, b, 2);
            say("round " + r + ": " + a.label() + "+" + b.label() + " and " + b.label() + "+" + a.label()
                    + " at once -> " + describe(p));
            if (p.won().isEmpty()) {
                nobody++;
            } else if (p.won().size() > 1) {
                twice++;
            }
        }
        why("For both to be refused, each hold must have read the claim the other had just written"
                + " on its first seat -- a write-read edge each way, which is a cycle. A hold only ever"
                + " meets another's claim through the EXCLUDE check, and that check never acts on an"
                + " uncommitted claim: it waits for the claim's transaction to commit or roll back,"
                + " then decides. So one of the two always finds either nothing or a finished booking,"
                + " and gets the pair. READ COMMITTED, PostgreSQL's lowest level, rules G1c out"
                + " outright. (On one showing the two also queue on its row lock, so here the second"
                + " does not even start until the first has finished.)");
        if (nobody > 0 || twice > 0) {
            return verdict(code, title, Verdict.OBSERVED, (nobody > 0 ? "nobody got the pair in " + nobody
                    + " round(s)" : "both got the pair in " + twice + " round(s)"));
        }
        return verdict(code, title, Verdict.NOT_OBSERVED, "in all " + rounds + " rounds exactly one of"
                + " the two got the pair; never neither");
    }

    /** G2-item: write skew on one known seat -- the double booking. */
    static Result g2item() {
        String code = "G2-item", title = "Item anti-dependency cycle (double booking)";
        heading(code, "item anti-dependency cycle", "two customers turn up with tickets for the same seat");
        Seat t = g2itemSeat.get(0);
        int attempts = 0, winnersTotal = 0, doubleSold = 0;
        List<String> odd = new ArrayList<>();
        for (int r = 1; r <= rounds; r++) {
            List<Function<HttpClient, Hold>> jobs = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                Account who = (i % 2 == 1 && c2 != null) ? c2 : c1;
                jobs.add(http -> hold(http, who, s1, List.of(t)));
            }
            List<Hold> hs = together(jobs);
            hs.forEach(h -> fatal(h, false));
            attempts += hs.size();
            List<Hold> won = hs.stream().filter(Hold::ok).toList();
            long taken = hs.stream().filter(Hold::taken).count();
            hs.stream().filter(h -> !h.ok() && !h.taken()).forEach(h -> odd.add(h.refusal()));
            long shown = readSeats(s1).stream().filter(s -> s.label().equals(t.label())
                    && !s.status().equals("available")).count();
            say("round " + r + ": " + RACERS + " holds on " + t.label() + " at once -> " + won.size()
                    + " held it, " + taken + " refused, just taken; the seat map shows it taken " + shown + "x");
            winnersTotal += won.size();
            if (won.size() > 1 || shown > 1) {
                doubleSold++;
            }
            for (Hold h : won) {
                release(h.who(), h.group());
            }
        }
        why("A booking that asked 'is this seat free?' and then inserted a claim would be write skew:"
                + " two of them each miss the other's claim, and READ COMMITTED -- REPEATABLE READ too"
                + " -- lets both through. This app never asks. Every hold just inserts its claim, and"
                + " the reservation_no_overlap EXCLUDE constraint rejects a second claim on a seat for"
                + " an overlapping time at write time (SQLSTATE 23P01), whatever the isolation level."
                + " Drop the constraint and SeatBookingConcurrencyDifferentialTest sees all eight succeed.");
        if (doubleSold > 0) {
            return verdict(code, title, Verdict.OBSERVED, t.label() + " was held more than once in "
                    + doubleSold + " round(s)");
        }
        if (winnersTotal != rounds || !odd.isEmpty()) {
            return verdict(code, title, Verdict.INCONCLUSIVE, "expected one winner per round, got "
                    + winnersTotal + " in " + rounds + (odd.isEmpty() ? "" : "; other refusal: " + odd.get(0)));
        }
        return verdict(code, title, Verdict.NOT_OBSERVED, attempts + " simultaneous holds, exactly one"
                + " winner in each of " + rounds + " rounds; every other one refused, just taken");
    }

    /** G1a: reading a write that was then rolled back. */
    static Result g1a() {
        String code = "G1a", title = "Aborted read";
        heading(code, "aborted read", "the seat map shows a claim from a booking that never went through");
        Seat a = anchor.get(0);
        int doomed = 8 * rounds;
        List<Hold> hs;
        Watch w = new Watch(s1);
        try {
            List<Function<HttpClient, List<Hold>>> lanes = new ArrayList<>();
            for (int lane = 0; lane < 2; lane++) {
                Account who = (lane == 1 && c2 != null) ? c2 : c1;
                lanes.add(http -> {
                    List<Hold> mine = new ArrayList<>();
                    for (int i = 0; i < doomed / 2; i++) {
                        mine.add(hold(http, who, s1, List.of(a)));
                    }
                    return mine;
                });
            }
            hs = together(lanes).stream().flatMap(List::stream).toList();
        } finally {
            w.close();
        }
        hs.forEach(h -> fatal(h, false));
        long rolledBack = hs.stream().filter(Hold::taken).count();
        long won = hs.stream().filter(Hold::ok).count();
        hs.stream().filter(Hold::ok).forEach(h -> release(h.who(), h.group()));
        int seen = 0;
        for (Watch.Poll p : w.polls) {
            Map<String, List<String>> map = byLabel(p.seats());
            boolean twice = map.values().stream().anyMatch(v -> v.size() > 1);
            if (twice || p.seats().size() != s1.seats.size()) {
                seen++;
            }
        }
        long overlapping = w.overlapping(hs);
        say(hs.size() + " doomed one-seat holds on " + a.label() + " (already held): each claim was written,"
                + " then undone by ROLLBACK TO SAVEPOINT after 23P01 -> " + rolledBack + " refused, just taken");
        say(w.polls.size() + " seat-map reads meanwhile, " + overlapping + " of them in flight alongside a"
                + " doomed hold; a seat shown twice (or the grid changing size): " + seen + "x");
        why("MVCC: a reader only ever sees committed row versions, and a rolled-back one is invisible to"
                + " everybody, forever. PostgreSQL has no dirty reads to switch on -- its READ UNCOMMITTED"
                + " behaves as READ COMMITTED -- so G1a cannot be produced here even on purpose.");
        if (seen > 0 || won > 0) {
            return verdict(code, title, Verdict.OBSERVED, seen + " read(s) showed a rolled-back claim"
                    + (won > 0 ? "; " + won + " doomed hold(s) succeeded" : ""));
        }
        if (rolledBack != hs.size()) {
            return verdict(code, title, Verdict.INCONCLUSIVE, "some doomed holds were refused for another reason");
        }
        return verdict(code, title, Verdict.NOT_OBSERVED, rolledBack + " rolled-back claims, "
                + w.polls.size() + " reads (" + overlapping + " overlapping), none saw a claim that was rolled back");
    }

    /** G1b: reading a version its own transaction later overwrote. */
    static Result g1b() {
        String code = "G1b", title = "Intermediate read";
        heading(code, "intermediate read", "seats flicker to 'held' during someone's failed block booking");
        if (g1bSeats.isEmpty()) {
            return skip(code, title, "the caps leave no room for a doomed block hold");
        }
        List<Seat> block = new ArrayList<>(g1bSeats);
        block.add(anchor.get(0));
        int doomed = 8 * rounds;
        List<Hold> hs;
        Watch w = new Watch(s1);
        try {
            List<Function<HttpClient, List<Hold>>> lanes = new ArrayList<>();
            for (int lane = 0; lane < 2; lane++) {
                Account who = (lane == 1 && c2 != null) ? c2 : c1;
                lanes.add(http -> {
                    List<Hold> mine = new ArrayList<>();
                    for (int i = 0; i < doomed / 2; i++) {
                        mine.add(hold(http, who, s1, block));
                    }
                    return mine;
                });
            }
            hs = together(lanes).stream().flatMap(List::stream).toList();
        } finally {
            w.close();
        }
        hs.forEach(h -> fatal(h, false));
        long refused = hs.stream().filter(Hold::taken).count();
        long won = hs.stream().filter(Hold::ok).count();
        hs.stream().filter(Hold::ok).forEach(h -> release(h.who(), h.group()));
        Set<String> watched = g1bSeats.stream().map(Seat::label).collect(Collectors.toSet());
        int seen = 0;
        for (Watch.Poll p : w.polls) {
            if (p.seats().stream().anyMatch(s -> watched.contains(s.label()) && !s.status().equals("available"))) {
                seen++;
            }
        }
        long overlapping = w.overlapping(hs);
        boolean freeAfter = readSeats(s1).stream().filter(s -> watched.contains(s.label()))
                .allMatch(s -> s.status().equals("available"));
        say(hs.size() + " doomed holds of " + names(block) + ": " + names(g1bSeats) + " written as 'held',"
                + " then " + anchor.get(0).label() + " fails and they are rewritten 'cancelled' before commit");
        say(w.polls.size() + " seat-map reads meanwhile, " + overlapping + " in flight alongside a doomed"
                + " hold; " + names(g1bSeats) + " seen held: " + seen + "x; all free afterwards: " + freeAfter);
        why("A reader sees each row as of the last commit before its statement began, never a version"
                + " its writer replaced before committing. The 'held' rows existed only inside the doomed"
                + " transaction; nobody else could ever read them.");
        if (seen > 0 || won > 0 || !freeAfter) {
            return verdict(code, title, Verdict.OBSERVED, seen + " read(s) showed a seat from a hold that"
                    + " withdrew it");
        }
        if (refused != hs.size()) {
            return verdict(code, title, Verdict.INCONCLUSIVE, "some doomed holds were refused for another reason");
        }
        return verdict(code, title, Verdict.NOT_OBSERVED, refused + " doomed block holds, " + w.polls.size()
                + " reads (" + overlapping + " overlapping), no intermediate 'held' ever visible");
    }

    /** G-single: a cycle with exactly one anti-dependency edge -- read skew. */
    static Result gSingle() {
        String code = "G-single", title = "Single anti-dependency cycle (read skew)";
        heading(code, "single anti-dependency cycle", "the seat map shows half of somebody's two-seat order");
        Seat a = gsingleSeats.get(0), b = gsingleSeats.get(1);
        Map<String, Integer> tally = new TreeMap<>();
        int torn = 0, polls = 0;
        for (int r = 1; r <= rounds; r++) {
            Watch w = new Watch(s1);
            String group = null;
            try {
                Hold h = hold(MAIN, c1, s1, List.of(a, b));
                fatal(h, false);
                if (!h.ok()) {
                    throw new Stop("G-single could not hold " + a.label() + "+" + b.label() + ": " + h.refusal());
                }
                group = h.group();
                pause(300);
                confirm(MAIN, c1, group);
                pause(300);
            } finally {
                w.close();
            }
            release(c1, group);
            for (Watch.Poll p : w.polls) {
                Map<String, List<String>> map = byLabel(p.seats());
                String sa = String.join("/", map.getOrDefault(a.label(), List.of("?")));
                String sb = String.join("/", map.getOrDefault(b.label(), List.of("?")));
                polls++;
                if (!sa.equals(sb)) {
                    torn++;
                    tally.merge("SPLIT " + sa + "|" + sb, 1, Integer::sum);
                } else {
                    tally.merge("both " + sa, 1, Integer::sum);
                }
            }
            say("round " + r + ": hold " + a.label() + "+" + b.label() + ", confirm, while 3 viewers poll -> "
                    + w.polls.size() + " reads");
        }
        say("what the reads saw: " + tally.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue())
                .collect(Collectors.joining(", ")));
        why("The seat map is one SQL statement, and under READ COMMITTED each statement reads a single"
                + " snapshot, so a commit is seen whole or not at all. READ COMMITTED does NOT prevent read"
                + " skew across two statements -- which is why every decision a hold makes from more than"
                + " one read is taken under a row lock instead (the showing FOR UPDATE, the seats FOR SHARE).");
        if (torn > 0) {
            return verdict(code, title, Verdict.OBSERVED, torn + " of " + polls + " reads showed the pair split");
        }
        return verdict(code, title, Verdict.NOT_OBSERVED, polls + " reads across " + rounds
                + " hold+confirm rounds; the pair was always in one state");
    }

    /** Adya's G-cursor, better known as P4, the lost update. */
    static Result gCursor() {
        String code = "G-cursor", title = "Lost update (P4)";
        heading(code, "lost update", "a customer cancels a seat and it stays sold anyway (each round:"
                + " hold two seats, then confirm the order and cancel one of them within a few ms)");
        Seat q1 = cursorSeats.get(0), q2 = cursorSeats.get(1);
        int lost = 0;
        Map<String, Integer> orders = new TreeMap<>();
        // A lost update needs the cancel to commit INSIDE the confirm's read-then-write window, and
        // how wide that window is lives inside the server. Released exactly together, one side
        // tends to win every time (measured: confirm won all of them against a local server, and a
        // fixed 15 ms head start for the cancel flipped the order in one run and not in the next).
        // So the rounds sweep an offset instead: positive = the confirm leaves first by that many
        // ms, negative = the cancel does. Twice as many rounds as the other probes, because each
        // one is only a handful of requests.
        int[] sweep = {0, -20, 20, 5, -5, 10, 2, -10, 15, -2};
        for (int r = 1; r <= 2 * rounds; r++) {
            int offset = sweep[(r - 1) % sweep.length];
            long confirmAfter = Math.max(0, -offset);
            long cancelAfter = Math.max(0, offset);
            String lead = offset == 0 ? "together" : offset > 0 ? "confirm " + offset + " ms ahead"
                    : "cancel " + (-offset) + " ms ahead";
            Hold h = hold(MAIN, c1, s1, List.of(q1, q2));
            fatal(h, false);
            if (!h.ok()) {
                throw new Stop("G-cursor could not hold " + q1.label() + "+" + q2.label() + ": " + h.refusal());
            }
            String group = h.group();
            String rid = order(c1, group).line(q1.label()).reservationId();
            together(List.<Function<HttpClient, Resp>>of(
                    http -> {
                        pause(confirmAfter);
                        return confirm(http, c1, group);
                    },
                    http -> {
                        pause(cancelAfter);
                        return cancel(http, c1, group, rid);
                    }));
            Order o = order(c1, group);
            String a = o.line(q1.label()).status(), b = o.line(q2.label()).status();
            String serial;
            if (a.equals("cancelled") && b.equals("confirmed") && "confirmed".equals(o.status())) {
                serial = "confirm, then cancel";
            } else if (a.equals("cancelled") && b.equals("held") && "pending".equals(o.status())) {
                serial = "cancel, then confirm (refused: a seat was no longer held)";
            } else {
                serial = "NO SERIAL ORDER: order " + o.status() + ", " + q1.label() + " " + a + ", " + q2.label() + " " + b;
                lost++;
            }
            orders.merge(serial, 1, Integer::sum);
            say("round " + r + " (" + lead + "): " + q1.label() + " " + a + ", " + q2.label() + " " + b
                    + ", order " + o.status() + "  = " + serial);
            release(c1, group);
        }
        why("Both writes are conditional UPDATEs -- confirm is WHERE status = 'held', cancel is WHERE"
                + " status IN ('held','confirmed'). Under READ COMMITTED the second writer waits for the"
                + " first, then re-checks its WHERE clause against the row the first committed, so it can"
                + " never overwrite a change it did not see. The ticket scanner's 'AND admitted_at IS NULL'"
                + " is the same guard (AdmissionRescanConcurrencyTest).");
        if (lost > 0) {
            return verdict(code, title, Verdict.OBSERVED, lost + " round(s) ended in a state no serial order produces");
        }
        return verdict(code, title, Verdict.NOT_OBSERVED, "the cancel was never lost; every round matched a"
                + " serial order " + orders);
    }

    /** G2: an anti-dependency cycle through a predicate read -- here, a COUNT of seats that the
     * other transaction's insert would have changed. */
    static Result g2() {
        String code = "G2", title = "Anti-dependency cycle over a predicate (phantom)";
        heading(code, "anti-dependency cycle over a predicate",
                "one customer with two tabs gets past the " + s1.perDay + "-seats-a-day limit");
        if (g2SeatsA.isEmpty()) {
            return skip(code, title, s1.perDay <= 0 ? "this deployment has no daily cap to race"
                    : "the caps leave no hold size that fits once but not twice");
        }
        Showing other = s2 == null ? s1 : s2;
        if (s2 != null && s1.startsAt != null && s2.startsAt != null
                && !s1.startsAt.toLocalDate().equals(s2.startsAt.toLocalDate())) {
            return skip(code, title, "showing 2 is on " + s2.startsAt.toLocalDate() + " and showing 1 on "
                    + s1.startsAt.toLocalDate() + "; the cap is per day, so they cannot race");
        }
        List<Hold> hs = together(List.of(
                http -> hold(http, c1, s1, g2SeatsA),
                http -> hold(http, c1, other, g2SeatsB)));
        hs.forEach(h -> fatal(h, true));
        List<Hold> won = hs.stream().filter(Hold::ok).toList();
        List<Hold> capped = hs.stream().filter(Hold::dayCap).toList();
        int size = g2SeatsA.size();
        say("customer 1 already holds " + anchorCount() + " seats that day; two holds of " + size
                + " at once -> " + hs.get(0).brief() + " | " + hs.get(1).brief());
        if (!capped.isEmpty()) {
            say("the refusal: \"" + capped.get(0).refusal() + "\"");
        }
        if (s2 == null) {
            say("(one showing only: both holds also queue on its row lock. ADYA_SHOWING_2 on the same"
                    + " day leaves the customer lock as the only thing serialising them.)");
        }
        for (Hold h : won) {
            release(h.who(), h.group());
        }
        why("The cap counts rows that do not exist yet, so there is nothing to lock and READ COMMITTED"
                + " lets both holds count " + anchorCount() + ", both insert, and end the day at "
                + (anchorCount() + 2 * size) + ". The hold locks the customer's own row"
                + " (SELECT ... FOR UPDATE) before counting, so one customer's holds count one at a time;"
                + " SERIALIZABLE would also catch it, the lock does it at READ COMMITTED."
                + " Take the lock out and DailyPurchaseCapConcurrencyDifferentialTest sees four holds"
                + " of three all land, twelve seats against a cap of ten.");
        if (won.size() > 1) {
            return verdict(code, title, Verdict.OBSERVED, "both holds succeeded: "
                    + (anchorCount() + 2 * size) + " seats against a cap of " + s1.perDay);
        }
        if (won.size() == 1 && capped.size() == 1) {
            return verdict(code, title, Verdict.NOT_OBSERVED, "one hold of " + size + " succeeded; the other"
                    + " was refused by the daily cap, having counted the first one's seats");
        }
        return verdict(code, title, Verdict.INCONCLUSIVE, "expected one success and one daily-cap refusal,"
                + " got " + won.size() + " success(es) and " + capped.size() + " cap refusal(s) -- does this"
                + " account have other seats that day?");
    }

    // ------------------------------------------------------------------------ HTTP

    static HttpClient client() {
        // HTTP/1.1 so that every client is its own TCP connection, the way separate phones are;
        // HTTP/2 would multiplex a race down one connection.
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static Resp get(HttpClient http, Account who, String path) {
        return send(http, who, path, null, false);
    }

    static Resp send(HttpClient http, Account who, String path, String form, boolean htmx) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Accept-Language", "en");
        if (who != null) {
            b.header("Cookie", who.cookie());
        }
        if (form != null) {
            b.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
            if (htmx) {
                b.header("HX-Request", "true");
            }
        } else {
            b.GET();
        }
        (form == null ? GETS : POSTS).incrementAndGet();
        long sent = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers(), sent, System.nanoTime());
        } catch (IOException e) {
            throw new IllegalStateException((form == null ? "GET " : "POST ") + path + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    static final Pattern ERROR_BANNER = Pattern.compile("<div class=\"error-banner\">\\s*(.*?)\\s*</div>", Pattern.DOTALL);

    /** The customer's own hold button, exactly: a form POST whose success is an HX-Redirect to the
     * new order and whose refusal is the seat map re-rendered with the reason in its banner. */
    static Hold hold(HttpClient http, Account who, Showing s, List<Seat> seats) {
        StringBuilder f = new StringBuilder();
        for (Seat seat : seats) {
            f.append("seatIds=").append(seat.id()).append('&');
        }
        f.append("customerName=").append(enc(name)).append("&customerPhone=").append(enc(phone));
        wrote = true;
        Resp r = send(http, who, s.path() + "/hold", f.toString(), true);
        String redirect = r.header("HX-Redirect");
        if (r.status() == 200 && redirect != null) {
            String group = redirect.substring(redirect.lastIndexOf('/') + 1);
            ledger.put(group, who);
            return new Hold(who, names(seats), true, group, null, r.sent(), r.done());
        }
        String banner = r.status() == 200 ? first(ERROR_BANNER, r.body()) : null;
        return new Hold(who, names(seats), false, null,
                banner == null ? "HTTP " + r.status() : unescape(banner).strip(), r.sent(), r.done());
    }

    /** Refusals that mean the run's premise is gone, as opposed to the answers a probe is after. */
    static void fatal(Hold h, boolean dayCapExpected) {
        if (h.ok()) {
            return;
        }
        String r = h.refusal();
        if (r.startsWith("HTTP 3") || r.equals("HTTP 401")) {
            throw new Stop(h.who().name() + "'s session ended mid-run (" + r + "). Sign in again and copy a fresh cookie.");
        }
        if (r.equals("HTTP 403")) {
            throw new Stop(h.who().name() + " may not hold seats (HTTP 403) -- use an ordinary customer account.");
        }
        if (r.startsWith("HTTP ")) {
            throw new Stop("a hold answered " + r + "; stopping rather than keep sending requests to a server in trouble.");
        }
        if (r.contains("not currently open")) {
            throw new Stop("the showing is not on sale: " + r);
        }
        if (r.contains("is rated")) {
            throw new Stop("the showing's film needs an age attestation: " + r);
        }
        if (r.contains("no longer part of this hall")) {
            throw new Stop("the seats no longer match the hall (was it re-laid out?): " + r);
        }
        if (r.startsWith("You can hold up to")) {
            throw new Stop("the per-booking cap refused a probe hold: " + r);
        }
        if (h.dayCap() && !dayCapExpected) {
            throw new Stop(h.who().name() + "'s daily limit is already used up on that day: " + r
                    + "\n    Use showings on a day this account has no other bookings.");
        }
    }

    static final Pattern GROUP_STATUS = Pattern.compile("class=\"badge ([a-z_]+)\"");
    static final Pattern ORDER_ROW = Pattern.compile(
            "<tr>\\s*<td>([^<]*)</td>\\s*<td>\\s*<span class=\"badge ([a-z_]+)\"(.*?)</tr>", Pattern.DOTALL);
    static final Pattern CANCEL_FORM = Pattern.compile("/items/([0-9a-fA-F-]{36})/cancel");

    static Order order(Account who, String group) {
        Resp r = get(MAIN, who, "/orders/" + group + "/fragment");
        if (r.status() != 200) {
            throw new IllegalStateException("GET /orders/" + group + "/fragment answered HTTP " + r.status());
        }
        List<Line> lines = new ArrayList<>();
        Matcher m = ORDER_ROW.matcher(r.body());
        while (m.find()) {
            // The cancel form is only rendered for a seat that is still live, so its presence is
            // also how the sweep tells what is left to give back.
            lines.add(new Line(unescape(m.group(1)).strip(), m.group(2), first(CANCEL_FORM, m.group(3))));
        }
        return new Order(group, first(GROUP_STATUS, r.body()), lines);
    }

    static Resp confirm(HttpClient http, Account who, String group) {
        Resp r = send(http, who, "/orders/" + group + "/confirm", "", false);
        if (r.status() != 303) {
            throw new IllegalStateException("confirm answered HTTP " + r.status());
        }
        return r;
    }

    static Resp cancel(HttpClient http, Account who, String group, String reservationId) {
        Resp r = send(http, who, "/orders/" + group + "/items/" + reservationId + "/cancel", "", false);
        if (r.status() != 303) {
            throw new IllegalStateException("cancel answered HTTP " + r.status());
        }
        return r;
    }

    /** Cancels every seat of an order that is still held or confirmed. */
    static void release(Account who, String group) {
        for (Line l : order(who, group).lines()) {
            if (l.reservationId() != null) {
                cancel(MAIN, who, group, l.reservationId());
            }
        }
        ledger.remove(group);
    }

    static void sweep() {
        if (ledger.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println("Giving back what this run still holds (" + ledger.size() + " order(s))...");
        for (Map.Entry<String, Account> e : new ArrayList<>(ledger.entrySet())) {
            try {
                release(e.getValue(), e.getKey());
            } catch (RuntimeException ex) {
                System.out.println("  could not release order " + e.getKey() + ": " + ex.getMessage()
                        + "\n  -- cancel it on " + base + "/orders");
            }
        }
    }

    // ------------------------------------------------------------------------ racing and watching

    static HttpClient lane(List<HttpClient> pool, int i) {
        synchronized (pool) {
            while (pool.size() <= i) {
                pool.add(client());
            }
            return pool.get(i);
        }
    }

    /** Runs the jobs at the same instant, each on its own connection. The connections are opened
     * first -- a TLS handshake takes far longer than the window being raced -- and only then are
     * all the jobs released together, the way several phones pressing Hold at once would be. */
    static <T> List<T> together(List<Function<HttpClient, T>> jobs) {
        int n = jobs.size();
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                HttpClient http = lane(LANES, i);
                Function<HttpClient, T> job = jobs.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        get(http, null, "/robots.txt");
                    } finally {
                        ready.countDown();
                    }
                    go.await();
                    return job.apply(http);
                }));
            }
            if (!ready.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("could not open " + n + " connections within 60 s");
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
        } catch (InterruptedException | TimeoutException e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Anonymous viewers reading the seat map over and over while a probe writes. Anonymous on
     * purpose: that is what a person looking at the seat map is, and it spares the server a
     * session check per read. */
    static final class Watch implements AutoCloseable {
        record Poll(long sent, long done, List<Seat> seats) {}

        final List<Poll> polls = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean on = new AtomicBoolean(true);
        final AtomicInteger errors = new AtomicInteger();
        final List<Thread> threads = new ArrayList<>();

        Watch(Showing s) {
            for (int i = 0; i < POLLERS; i++) {
                HttpClient http = lane(VIEWERS, i);
                Thread t = new Thread(() -> {
                    while (on.get()) {
                        try {
                            Resp r = get(http, null, s.fragment());
                            if (r.status() == 200) {
                                polls.add(new Poll(r.sent(), r.done(), seats(r.body())));
                            } else {
                                errors.incrementAndGet();
                            }
                            Thread.sleep(POLL_PAUSE_MS);
                        } catch (InterruptedException e) {
                            return;
                        } catch (RuntimeException e) {
                            errors.incrementAndGet();
                        }
                    }
                }, "adya-viewer-" + i);
                t.setDaemon(true);
                t.start();
                threads.add(t);
            }
            pause(400); // every viewer has a read in flight before the first write goes out
        }

        /** Reads whose round trip overlapped the round trip of some hold. Client-side timing, so an
         * upper bound on how many reads could have met a hold in the database -- reported so the
         * reader can judge how hard the window was actually tried. */
        long overlapping(List<Hold> holds) {
            synchronized (polls) {
                return polls.stream().filter(p -> holds.stream()
                        .anyMatch(h -> p.sent() < h.done() && h.sent() < p.done())).count();
            }
        }

        @Override
        public void close() {
            on.set(false);
            for (Thread t : threads) {
                try {
                    t.join(35_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ------------------------------------------------------------------------ parsing

    /** Both shapes a seat takes in the grid: an available one is a label wrapping a checkbox that
     * carries its id, a taken one a bare span with no id at all (the seat map's revert depends on
     * exactly that -- see CLAUDE.md). */
    static final Pattern SEAT = Pattern.compile(
            "<label class=\"seat available\" title=\"([^\"]*)\"\\s+id=\"seat-([0-9a-fA-F-]{36})\""
                    + "|<span class=\"seat ([a-z]+)\" title=\"[^\"]*\">([^<]*)</span>");

    static List<Seat> seats(String html) {
        List<Seat> out = new ArrayList<>();
        Matcher m = SEAT.matcher(html);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(new Seat(unescape(m.group(1)), "available", m.group(2).toLowerCase(Locale.ROOT)));
            } else {
                out.add(new Seat(unescape(m.group(4)).strip(), m.group(3), null));
            }
        }
        return out;
    }

    static List<Seat> readSeats(Showing s) {
        Resp r = get(MAIN, null, s.fragment());
        if (r.status() != 200) {
            throw new IllegalStateException("GET " + s.fragment() + " answered HTTP " + r.status());
        }
        return seats(r.body());
    }

    static Map<String, List<String>> byLabel(List<Seat> seats) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Seat s : seats) {
            m.computeIfAbsent(s.label(), k -> new ArrayList<>()).add(s.status());
        }
        return m;
    }

    // ------------------------------------------------------------------------ reporting

    static void record(Result r) {
        results.add(r);
        System.out.println("   => " + r.verdict().toString().replace('_', ' ') + ": " + r.line());
    }

    static Result verdict(String code, String name, Verdict v, String line) {
        return new Result(code, name, v, line);
    }

    static Result skip(String code, String name, String why) {
        return new Result(code, name, Verdict.SKIPPED, why);
    }

    static void heading(String code, String name, String cinema) {
        System.out.println();
        System.out.println("[" + code + "] " + name);
        System.out.println("   at the cinema: " + cinema);
    }

    static void say(String s) {
        System.out.println("   " + s);
    }

    static void why(String s) {
        StringBuilder line = new StringBuilder("   why not here:");
        for (String word : s.split(" ")) {
            if (line.length() + word.length() + 1 > 96) {
                System.out.println(line);
                line = new StringBuilder("     ");
            }
            line.append(' ').append(word);
        }
        System.out.println(line);
    }

    static int summary(int floor) {
        if (!wrote && results.isEmpty()) {
            return floor; // stopped before the first write: the message above is the whole story
        }
        System.out.println();
        System.out.println("=".repeat(78));
        if (s1 != null && base != null) {
            try {
                List<Seat> left = readSeats(s1).stream().filter(s -> !s.status().equals("available")).toList();
                System.out.println("Showing 1 after the run: " + (left.isEmpty() ? "every seat free again."
                        : "still taken -- " + left.stream().map(s -> s.label() + " " + s.status())
                        .collect(Collectors.joining(", "))));
            } catch (RuntimeException e) {
                System.out.println("Could not re-read the seat map: " + e.getMessage());
            }
        }
        int exit = floor;
        for (Result r : results) {
            System.out.printf("  %-9s %-50s %s%n", r.code(), r.name(), r.verdict().toString().replace('_', ' '));
            if (r.verdict() == Verdict.OBSERVED) {
                exit = 1;
            } else if (r.verdict() == Verdict.INCONCLUSIVE && exit == 0) {
                exit = 2;
            }
        }
        long ran = results.stream().filter(r -> r.verdict() != Verdict.SKIPPED).count();
        System.out.println(exit == 0 ? "  " + ran + " probe(s) ran; no anomaly was observed."
                : exit == 1 ? "  AN ANOMALY WAS OBSERVED -- see above."
                : "  The run did not complete cleanly -- see above.");
        System.out.printf("  %d writes (POST) and %d reads (GET) in %.0f s.%n", POSTS.get(), GETS.get(),
                (System.nanoTime() - STARTED) / 1e9);
        System.out.println("  Not probed: a showing cancelled mid-checkout. It is not an Adya anomaly (one"
                + "\n  anti-dependency edge, no cycle) and needs a staff account; see the guide.");
        return exit;
    }

    // ------------------------------------------------------------------------ small things

    static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? null : v.strip();
    }

    static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static int intOr(String s, int fallback) {
        return s == null ? fallback : Integer.parseInt(s);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String unescape(String s) {
        return s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    static String names(List<Seat> seats) {
        return seats.stream().map(Seat::label).collect(Collectors.joining("+"));
    }

    static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

int adyaExit = Adya.run();
/exit adyaExit
/exit 2
