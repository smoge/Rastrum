// ScoreChange: one diff and the two scores it compares, held together.
//
// `ScoreDiff` answers deltas. To resolve one back to an element, a caller must
// also supply the old or new score. This class keeps those scores beside the
// diff: one handle, no new rules.
//
// Same boundary as `ScoreDiff`: no preparation, validation, patch, revert, move
// detection or prior-change list.

// A delta says kind, address, old and new. The address is a child-index path
// from the score root; it resolves only against the matching old or new score.
//
// `oldElementFor` and `newElementFor` supply the stored scores, so callers pass
// only the delta.
ScoreChange {
    // oldScore: MusicScore, newScore: MusicScore
    // deltas: [IdentityDictionary], label: String | Nil
    var <oldScore, <newScore, <deltas, <label;

    // `ScoreDiff.between` owns the MusicScore checks.
    //
    // >>> ScoreChange.between(MusicScore.oneStaff(Measure("2/4", "c4 d4")), MusicScore.oneStaff(Measure("2/4", "c4 e4"))).changed
    // true
    //
    // ^ Self
    *between { |oldScore, newScore, label|
        ^super.newCopyArgs(oldScore, newScore,
            ScoreDiff.between(oldScore, newScore),
            ScoreChange.checkedLabel(label))
    }

    // Optional prose, like a staff name. `nil` says nothing, a blank String is refused.
    //
    // ^ String | Nil
    *checkedLabel { |value|
        if (value.isNil) { ^nil };
        if (value.isKindOf(String).not) {
            Error(
                "ScoreChange: label must be a String or nil, got % (%)."
                .format(value.asCompileString, value.class)
            ).throw
        };
        if (value.stripWhiteSpace.isEmpty) {
            Error(
                "ScoreChange: empty label. Use nil for no label, not %."
                .format(value.asCompileString)
            ).throw
        };
        ^value
    }

    // ^ Boolean
    changed { ^deltas.notEmpty }

    // ^ Boolean
    noDifference { ^ScoreDiff.noDifference(deltas) }

    // ^ [[Integer]]
    addresses { ^ScoreDiff.addresses(deltas) }

    // ^ [IdentityDictionary]
    deltasAt { |address| ^ScoreDiff.deltasAt(deltas, address) }

    // ^ [IdentityDictionary]
    deltasUnder { |address| ^ScoreDiff.deltasUnder(deltas, address) }

    // >>> ScoreChange.between(
    //     MusicScore.oneStaff(Measure("2/4", "c4 d4")),
    //     MusicScore.oneStaff(Measure("2/4", "c4 e4"))).countByKind[\pitchChanged]
    // 1
    //
    // ^ Dictionary
    countByKind { ^ScoreDiff.countByKind(deltas) }

    // The stored scores supply `ScoreDiff`'s second argument.
    //
    // ^ ScoreElement | Nil
    oldElementFor { |delta| ^ScoreDiff.oldElementFor(delta, oldScore) }
    // ^ ScoreElement | Nil
    newElementFor { |delta| ^ScoreDiff.newElementFor(delta, newScore) }

    // ^ Self
    printOn { |stream|
        stream << "ScoreChange(" << deltas.size << " delta"
            << if (deltas.size == 1) { "" } { "s" };
        label !? { stream << ", " << label.asCompileString };
        stream << ")"
    }
}
