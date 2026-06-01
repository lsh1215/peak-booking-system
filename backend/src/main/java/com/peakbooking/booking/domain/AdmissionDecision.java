package com.peakbooking.booking.domain;

public record AdmissionDecision(
        AdmissionResult result,
        Long admissionId,
        Long dbAdmissionSeq,
        Long redisSeq,
        int candidateRank,
        GateMode gateMode
) {

    public static AdmissionDecision admitted(
            long admissionId,
            long dbAdmissionSeq,
            Long redisSeq,
            int candidateRank,
            GateMode gateMode
    ) {
        return new AdmissionDecision(
                AdmissionResult.ADMITTED,
                admissionId,
                dbAdmissionSeq,
                redisSeq,
                candidateRank,
                gateMode
        );
    }

    public static AdmissionDecision rejected(GateMode gateMode) {
        return new AdmissionDecision(AdmissionResult.REJECTED, null, null, null, 0, gateMode);
    }
}
