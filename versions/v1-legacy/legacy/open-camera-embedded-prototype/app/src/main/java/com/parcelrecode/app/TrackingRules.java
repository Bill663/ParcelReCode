package com.parcelrecode.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrackingRules {
    private static final Pattern UNIUNI = Pattern.compile("UUS[A-Z0-9]{16}");
    private static final Pattern GOFO = Pattern.compile("GFUS\\d{14}");
    private static final Pattern SWIFTX = Pattern.compile("SWX\\d{18}");
    private static final Pattern SPEEDX = Pattern.compile("SPX[A-Z]{3}\\d{12}");
    private static final Pattern ONTRAC = Pattern.compile("1LSD[A-Z0-9]{11}");
    private static final Pattern USPS = Pattern.compile("(?<!\\d)9\\d{21}(?!\\d)");
    private static final Pattern FEDEX = Pattern.compile("(?<!\\d)\\d{12}(?!\\d)");
    private static final Pattern FEDEX_MACHINE = Pattern.compile("96\\d{32}");
    private static final Pattern USPS_MACHINE = Pattern.compile("420(\\d{5,9})92(\\d{20})");
    private static final Pattern ADDRESS_ZIP = Pattern.compile("\\b[A-Z]{2}\\s+(\\d{5})(?:-\\d{4})?\\b");

    private TrackingRules() {}

    public static final class Candidate {
        public final String company;
        public final String carrier;
        public final String tracking;
        public final String zipCode;
        public final String machineValue;
        public final String source;
        public final int score;

        public Candidate(
                String company,
                String carrier,
                String tracking,
                String zipCode,
                String machineValue,
                String source,
                int score
        ) {
            this.company = company;
            this.carrier = carrier;
            this.tracking = tracking;
            this.zipCode = zipCode == null ? "" : zipCode;
            this.machineValue = machineValue == null ? "" : machineValue;
            this.source = source;
            this.score = score;
        }

        Candidate withSource(String nextSource, int nextScore) {
            return new Candidate(company, carrier, tracking, zipCode, machineValue, nextSource, nextScore);
        }

        public boolean isFedExMachine() {
            return "fedex".equals(company) && machineValue.matches("\\d{34}");
        }
    }

    public static String clean(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    public static Candidate parseMachineValue(String rawValue) {
        String raw = rawValue == null ? "" : rawValue.toUpperCase(Locale.US).trim();
        String compact = clean(raw);

        Candidate direct = parseDirect(compact, "barcode", 250);
        if (direct == null) direct = parseEmbeddedPrefixed(compact, "structured barcode", 300);
        if (direct != null && !"fedex".equals(direct.company) && !"usps".equals(direct.company)) {
            return direct;
        }

        Matcher fedEx = FEDEX_MACHINE.matcher(compact);
        if (fedEx.find()) {
            String machine = fedEx.group();
            return new Candidate(
                    "fedex", "FedEx", machine.substring(machine.length() - 12), "", machine, "lower FedEx line", 500
            );
        }

        Matcher usps = USPS_MACHINE.matcher(compact);
        if (usps.find()) {
            String zip = usps.group(1).substring(0, 5);
            return new Candidate("usps", "USPS", "92" + usps.group(2), zip, "", "USPS barcode", 400);
        }

        if (direct != null) return direct;
        return null;
    }

    public static List<Candidate> extractTextCandidates(String text) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        String[] lines = (text == null ? "" : text.toUpperCase(Locale.US)).split("\\R");
        List<String> compactLines = new ArrayList<>();
        for (String line : lines) compactLines.add(clean(line));

        for (int index = 0; index < compactLines.size(); index++) {
            String line = compactLines.get(index);
            StringBuilder nearbyBuilder = new StringBuilder();
            for (int nearby = Math.max(0, index - 1); nearby <= Math.min(compactLines.size() - 1, index + 1); nearby++) {
                nearbyBuilder.append(compactLines.get(nearby));
            }
            String nearby = nearbyBuilder.toString();

            Matcher machineMatcher = FEDEX_MACHINE.matcher(line);
            while (machineMatcher.find()) {
                Candidate parsed = parseMachineValue(machineMatcher.group());
                if (parsed != null) putBest(candidates, parsed.withSource("OCR lower FedEx line", 460));
            }

            extractPrefixed(candidates, line, UNIUNI, "uniuni", "UniUni");
            extractPrefixed(candidates, line, GOFO, "gofo", "GOFO");
            extractPrefixed(candidates, line, SWIFTX, "swiftx", "SwiftX");
            extractPrefixed(candidates, line, SPEEDX, "speedx", "SpeedX");
            extractPrefixed(candidates, line, ONTRAC, "ontrac", "OnTrac");

            Matcher uspsMatcher = USPS.matcher(line);
            while (uspsMatcher.find()) {
                String zip = findNearbyAddressZip(lines, index);
                putBest(candidates, new Candidate(
                        "usps",
                        "USPS",
                        uspsMatcher.group(),
                        zip,
                        "",
                        zip.isEmpty() ? "OCR" : "OCR with ZIP",
                        zip.isEmpty() ? 180 : 280));
            }

            if (nearby.contains("FEDEX") || nearby.contains("TRACKINGID")) {
                Matcher fedExMatcher = FEDEX.matcher(line);
                while (fedExMatcher.find()) {
                    putBest(candidates, new Candidate("fedex", "FedEx", fedExMatcher.group(), "", "", "FedEx Tracking ID", 650));
                }
            }
        }
        return new ArrayList<>(candidates.values());
    }

    public static Candidate chooseBest(List<Candidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingInt(candidate -> candidate.isFedExMachine() ? candidate.score + 1000 : candidate.score))
                .orElse(null);
    }

    public static String companyForTracking(String trackingValue) {
        Candidate candidate = parseDirect(clean(trackingValue), "manual", 1);
        return candidate == null ? "" : candidate.company;
    }

    public static String formatQrPayload(String trackingValue, String zipCode, String machineValue) {
        String tracking = clean(trackingValue);
        return formatQrPayload(tracking, zipCode, machineValue, companyForTracking(tracking));
    }

    public static String formatQrPayload(
            String trackingValue,
            String zipCode,
            String machineValue,
            String selectedCompany
    ) {
        String tracking = clean(trackingValue);
        String company = selectedCompany == null || selectedCompany.isEmpty()
                ? companyForTracking(tracking)
                : selectedCompany;
        if (tracking.isEmpty()) return null;

        if ("usps".equals(company)) {
            String zip = zipCode == null ? "" : zipCode.replaceAll("\\D", "");
            if (zip.length() < 5) return null;
            return "420" + zip.substring(0, 5) + tracking;
        }

        if ("fedex".equals(company)) {
            String machine = clean(machineValue);
            if (machine.matches("\\d{34}") && machine.endsWith(tracking)) {
                return machine.substring(0, 9) + "0207286700" + machine.substring(19);
            }
            return "9631091350207286700400" + tracking;
        }
        return tracking;
    }

    private static Candidate parseDirect(String compact, String source, int score) {
        if (UNIUNI.matcher(compact).matches()) return new Candidate("uniuni", "UniUni", compact, "", "", source, score);
        if (GOFO.matcher(compact).matches()) return new Candidate("gofo", "GOFO", compact, "", "", source, score);
        if (SWIFTX.matcher(compact).matches()) return new Candidate("swiftx", "SwiftX", compact, "", "", source, score);
        if (SPEEDX.matcher(compact).matches()) return new Candidate("speedx", "SpeedX", compact, "", "", source, score);
        if (ONTRAC.matcher(compact).matches()) return new Candidate("ontrac", "OnTrac", compact, "", "", source, score);
        if (compact.matches("9\\d{21}")) return new Candidate("usps", "USPS", compact, "", "", source, score);
        if (compact.matches("\\d{12}")) return new Candidate("fedex", "FedEx", compact, "", "", source, score);
        return null;
    }

    private static Candidate parseEmbeddedPrefixed(String compact, String source, int score) {
        Candidate candidate = findEmbedded(compact, UNIUNI, "uniuni", "UniUni", source, score);
        if (candidate != null) return candidate;
        candidate = findEmbedded(compact, GOFO, "gofo", "GOFO", source, score);
        if (candidate != null) return candidate;
        candidate = findEmbedded(compact, SWIFTX, "swiftx", "SwiftX", source, score);
        if (candidate != null) return candidate;
        candidate = findEmbedded(compact, SPEEDX, "speedx", "SpeedX", source, score);
        if (candidate != null) return candidate;
        return findEmbedded(compact, ONTRAC, "ontrac", "OnTrac", source, score);
    }

    private static Candidate findEmbedded(
            String compact,
            Pattern pattern,
            String company,
            String carrier,
            String source,
            int score
    ) {
        Matcher matcher = pattern.matcher(compact);
        if (!matcher.find()) return null;
        return new Candidate(company, carrier, matcher.group(), "", "", source, score);
    }

    private static void extractPrefixed(
            Map<String, Candidate> candidates,
            String line,
            Pattern pattern,
            String company,
            String carrier
    ) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            putBest(candidates, new Candidate(company, carrier, matcher.group(), "", "", "OCR", 190));
        }
    }

    private static void putBest(Map<String, Candidate> candidates, Candidate candidate) {
        Candidate existing = candidates.get(candidate.tracking);
        if (existing == null
                || candidate.score > existing.score
                || (candidate.isFedExMachine() && !existing.isFedExMachine())
                || (existing.zipCode.isEmpty() && !candidate.zipCode.isEmpty())) {
            candidates.put(candidate.tracking, candidate);
        }
    }

    private static String findNearbyAddressZip(String[] lines, int index) {
        String zip = "";
        int start = Math.max(0, index - 8);
        int end = Math.min(lines.length - 1, index + 2);
        for (int nearby = start; nearby <= end; nearby++) {
            Matcher matcher = ADDRESS_ZIP.matcher(lines[nearby]);
            while (matcher.find()) {
                zip = matcher.group(1);
            }
        }
        return zip;
    }
}
