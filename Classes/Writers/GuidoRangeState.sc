// GuidoRangeState: one open GUIDO range and its refusal.
//
// GUIDO allows one open range of each kind. Slurs, beams, hairpins,
// glissandi and tempo ramps all ask the same three questions: can
// this id open, can it close, is anything still open at the end?
//
// Writing stays in `GuidoWriter`.
//
// This class only tracks range state and keeps each kind's refusal
// words. Ties stay separate because they pair by pitch, not by id.
//
// More complex range-form support needs sequence-level planning. This
// class only tracks one open id.

// Refusal text is caller-facing. Keep the old kind-specific phrases instead of
// deriving them from symbols. "a tempo ramp stop with id 2" is not the same
// message as "tempoRamp id 2".
GuidoRangeState {
    // How a dangling range names itself, with `%` for the id.
    //
    // How one endpoint names itself. A glissando dangles as "a
    // glissando with id 1" and mismatches as "glissando id 1".
    //
    // The three sayings are refusal endings.
    var <subject, <endpointSubject;
    var <overlapSaying, <mismatchSaying, <danglingSaying;
    var <openId, <payload;

    // >>> GuidoRangeState("slur id %", "slur id %").isOpen   -> false
    //
    // ^ Self
    *new { |subject, endpointSubject, overlapSaying = "", mismatchSaying = "",
        danglingSaying = ""|
        ^super.newCopyArgs(subject, endpointSubject, overlapSaying,
            mismatchSaying, danglingSaying)
    }

    // The five kinds, as the writer opens them. Built fresh per
    // sequence: a range does not reach across staves.
    //
    // >>> GuidoRangeState.slur.subject   -> slur id %
    //
    // ^ Self
    *slur {
        ^this.new("slur id %", "slur id %",
            " GUIDO slur endpoints carry no id.",
            " GUIDO slur endpoints carry no id.",
            "A slur may cross a barline into the same voice of the next bar, "
            "but it needs a following note.")
    }

    // ^ Self
    *beam {
        ^this.new("beam id %", "beam id %", "", "", "A beam needs a following note.")
    }

    // ^ Self
    *hairpin {
        ^this.new("hairpin id %", "hairpin id %", "", "", "A hairpin needs a following note.")
    }

    // ^ Self
    *glissando {
        ^this.new("a glissando with id %", "glissando id %",
            " GUIDO draws one range at a time.", "", "A range tag needs both ends.")
    }

    // The ramp's own overlap refusal stays in the writer: a ramp is a
    // bar direction read before any leaf is written and says so in
    // different words. See `prTrackRamp`, which is why the endpoint
    // subject here is the stop's alone.
    //
    // ^ Self
    *tempoRamp {
        ^this.new("a tempo ramp with id %", "a tempo ramp stop with id %", "", "", "A range tag needs both ends.")
    }

    // >>> GuidoRangeState.beam.opened(1).isOpen   -> true
    // ^ Boolean
    isOpen { ^openId.notNil }

    // A range that may open, with what it carries while open. The
    // payload is a hairpin's direction, which its stop tag reads
    // from.
    //
    // ^ Self
    opened { |id, carried|
        if (openId.notNil) {
            Error(
                "GuidoWriter: % opens while id % is still open.%"
                .format(endpointSubject.format(id), openId, overlapSaying)
            ).throw
        };
        ^this.openedUnchecked(id, carried)
    }

    // The same without the overlap refusal, for a caller that has
    // already asked in its own words.
    //
    // ^ Self
    openedUnchecked { |id, carried|
        openId = id;
        payload = carried;
        ^this
    }

    // The range closed, answering what it carried. Refuses a stop
    // that opened nothing and one that names another id.
    //
    // >>> GuidoRangeState.hairpin.opened(1, \crescendo).closed(1)
    // crescendo
    //
    // ^ Object
    closed { |id|
        var carried = payload;
        if (openId.isNil) {
            Error(
                "GuidoWriter: % closes nothing."
                .format(endpointSubject.format(id))
            ).throw
        };
        if (id != openId) {
            Error(
                "GuidoWriter: % closes where id % is open.%"
                .format(endpointSubject.format(id), openId, mismatchSaying)
            ).throw
        };
        openId = nil;
        payload = nil;
        ^carried
    }

    // `ending` says where the music ran out, which is the staff or
    // the whole write. Both callers are `GuidoWriter`.
    //
    // ^ Self
    requireClosed { |ending|
        if (openId.notNil) {
            Error(
                "GuidoWriter: % is still open, but %. %"
                .format(subject.format(openId), ending, danglingSaying)
            ).throw
        };
        ^this
    }

    printOn { |stream|
        stream << "GuidoRangeState(";
        if (openId.isNil) { stream << "closed" }
            { stream << "open " << openId };
        stream << ")"
    }
}
