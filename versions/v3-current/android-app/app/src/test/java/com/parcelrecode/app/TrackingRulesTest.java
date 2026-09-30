package com.parcelrecode.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackingRulesTest {
    @Test
    public void fedExUsesCompleteLowerMachineLineAndReplacesTenDigitBlock() {
        String tracking = "382125368988";
        String original = "9631091351234567890400" + tracking;
        TrackingRules.Candidate candidate = TrackingRules.parseMachineValue(original);

        assertNotNull(candidate);
        assertTrue(candidate.isFedExMachine());
        assertEquals(tracking, candidate.tracking);
        assertEquals(
                "9631091350207286700400" + tracking,
                TrackingRules.formatQrPayload(candidate.tracking, "", candidate.machineValue)
        );
    }

    @Test
    public void uspsUsesDestinationZipAndTwentyTwoDigitTracking() {
        String machine = "420433429236290396812301838627";
        TrackingRules.Candidate candidate = TrackingRules.parseMachineValue(machine);

        assertNotNull(candidate);
        assertEquals("usps", candidate.company);
        assertEquals("43342", candidate.zipCode);
        assertEquals("9236290396812301838627", candidate.tracking);
        assertEquals(machine, TrackingRules.formatQrPayload(candidate.tracking, candidate.zipCode, ""));
    }

    @Test
    public void structuredBarcodeCanContainAnOnTracTrackingNumber() {
        TrackingRules.Candidate candidate = TrackingRules.parseMachineValue(
                "1LSD96I000ZLQRU|2|D96I|TKTKFOCA|GRND|KNOX|005|US|TN|37814-4274|"
        );

        assertNotNull(candidate);
        assertEquals("ontrac", candidate.company);
        assertEquals("1LSD96I000ZLQRU", candidate.tracking);
    }

    @Test
    public void onTracOcrAllowsLeadingOneReadAsLetter() {
        java.util.List<TrackingRules.Candidate> candidates = TrackingRules.extractTextCandidates(
                "CBT LAX\n"
                        + "lLSD96I00 106RN7\n"
                        + "GRND\n"
                        + "CEDAR HILL, TX 75104-8120"
        );

        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        assertNotNull(best);
        assertEquals("ontrac", best.company);
        assertEquals("1LSD96I00106RN7", best.tracking);
    }

    @Test
    public void onTracOcrRepairsCommonPrefixAndSymbolConfusions() {
        java.util.List<TrackingRules.Candidate> candidates = TrackingRules.extractTextCandidates(
                "125D96IO010€4LO"
        );

        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        assertNotNull(best);
        assertEquals("ontrac", best.company);
        assertEquals("1LSD96I0010C4LO", best.tracking);
    }

    @Test
    public void gofoOcrRepairsLetterOInNumericTracking() {
        java.util.List<TrackingRules.Candidate> candidates = TrackingRules.extractTextCandidates(
                "SHIP FROM\nGFUSO105:851 7542208\nCBTGofo Fontana"
        );

        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        assertNotNull(best);
        assertEquals("gofo", best.company);
        assertEquals("GFUS01058517542208", best.tracking);
    }

    @Test
    public void uniUniOcrRepairsMissingSecondSInPrefix() {
        java.util.List<TrackingRules.Candidate> candidates = TrackingRules.extractTextCandidates(
                "Tracking Number:\n"
                        + "UU566R5590442879273"
        );

        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        assertNotNull(best);
        assertEquals("uniuni", best.company);
        assertEquals("UUS66R5590442879273", best.tracking);
    }

    @Test
    public void uspsOcrRepairsCommonDigitLetterConfusions() {
        java.util.List<TrackingRules.Candidate> candidates = TrackingRules.extractTextCandidates(
                "ANISA WILSON\n"
                        + "150 ESTUARY DR\n"
                        + "LA GRANGE NC 28551\n"
                        + "USPS TRACKING # USPS Ship\n"
                        + "92OO19O3968I236872O9O1"
        );

        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        assertNotNull(best);
        assertEquals("usps", best.company);
        assertEquals("28551", best.zipCode);
        assertEquals("9200190396812368720901", best.tracking);
    }

    @Test
    public void explicitFedExTrackingIdOutranksUspsHandoffBarcode() {
        java.util.List<TrackingRules.Candidate> candidates = new java.util.ArrayList<>();
        candidates.add(TrackingRules.parseMachineValue("420816259261290391865171075921"));
        candidates.addAll(TrackingRules.extractTextCandidates("FedEx Tracking ID# 3821 2873 5008"));

        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        assertNotNull(best);
        assertEquals("fedex", best.company);
        assertEquals("382128735008", best.tracking);
    }

    @Test
    public void manualCarrierOverrideCanForceFedExQrPayload() {
        assertEquals(
                "9631091350207286700400382128735008",
                TrackingRules.formatQrPayload("3821 2873 5008", "", "", "fedex")
        );
    }

    @Test
    public void manualCarrierOverrideCanForceUspsZipQrPayload() {
        assertEquals(
                "420433429236290396812301838627",
                TrackingRules.formatQrPayload("9236290396812301838627", "43342", "", "usps")
        );
    }

    @Test
    public void uspsOcrTrackingUsesNearbyDestinationZip() {
        java.util.List<TrackingRules.Candidate> candidates = TrackingRules.extractTextCandidates(
                "SHIP TO\n"
                        + "TONI SEARS\n"
                        + "16255 EMERALD PT\n"
                        + "CLEVELAND, OH 44130-8368\n"
                        + "USPS TRACKING #\n"
                        + "9236290396812301838627"
        );

        TrackingRules.Candidate best = TrackingRules.chooseBest(candidates);
        assertNotNull(best);
        assertEquals("usps", best.company);
        assertEquals("44130", best.zipCode);
        assertEquals(
                "420441309236290396812301838627",
                TrackingRules.formatQrPayload(best.tracking, best.zipCode, best.machineValue)
        );
    }

    @Test
    public void uspsZipFromOcrCanFillSameTrackingCandidate() {
        java.util.Map<String, TrackingRules.Candidate> byTracking = new java.util.LinkedHashMap<>();
        java.util.List<TrackingRules.Candidate> candidates = new java.util.ArrayList<>();
        candidates.add(TrackingRules.parseMachineValue("9236290396812301838627"));
        candidates.addAll(TrackingRules.extractTextCandidates(
                "MARY SMITH\n"
                        + "45 MARKET ST\n"
                        + "MARION, OH 43342-1234\n"
                        + "9236290396812301838627"
        ));
        for (TrackingRules.Candidate candidate : candidates) {
            byTracking.put(candidate.tracking, candidate);
        }

        TrackingRules.Candidate best = TrackingRules.chooseBest(new java.util.ArrayList<>(byTracking.values()));
        assertNotNull(best);
        assertEquals("43342", best.zipCode);
    }
}
