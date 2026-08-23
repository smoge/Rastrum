// ScoreChange: one diff and the two scores it is about, held together.
//
// `ScoreDiff` answers deltas and takes the scores back for lookup.
// This class holds all three together: one handle, no new rules.
//
// Same boundary as `ScoreDiff`: no preparation, validation, patch,
// revert, move detection or earlier-change list.


// Note [Holding the scores is what makes a delta readable]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A delta says kind, address, old and new. The address is only
// meaningful against the score it counts from.
//
// `oldElementFor` and `newElementFor` take a delta alone because the
// scores are already known here.
ScoreChange {
    var <oldScore, <newScore, <deltas, <label;

    // `ScoreDiff.between` owns score-shape refusals.
    //
    // >>> ScoreChange.between(MusicScore.oneStaff(Measure("2/4", "c4 d4")),
    //     MusicScore.oneStaff(Measure("2/4", "c4 e4"))).changed
    // true
    *between { |oldScore, newScore, label|
        ^super.newCopyArgs(oldScore, newScore,
            ScoreDiff.between(oldScore, newScore),
            ScoreChange.checkedLabel(label))
    }

    // Prose, like a staff name. nil says nothing; empty String is refused.
    *checkedLabel { |value|
        if (value.isNil) { ^nil };
        if (value.isKindOf(String).not) {
            Error("ScoreChange: label must be a String or nil, got % (%)."
                .format(value.asCompileString, value.class)).throw
        };
        if (value.stripWhiteSpace.isEmpty) {
            Error("ScoreChange: empty label. Use nil for no label, not %."
                .format(value.asCompileString)).throw
        };
        ^value
    }

    changed { ^deltas.notEmpty }
    noDifference { ^ScoreDiff.noDifference(deltas) }

    addresses { ^ScoreDiff.addresses(deltas) }
    deltasAt { |address| ^ScoreDiff.deltasAt(deltas, address) }
    deltasUnder { |address| ^ScoreDiff.deltasUnder(deltas, address) }

    // >>> ScoreChange.between(
    //     MusicScore.oneStaff(Measure("2/4", "c4 d4")),
    //     MusicScore.oneStaff(Measure("2/4", "c4 e4"))).countByKind[\pitchChanged]
    // 1
    countByKind { ^ScoreDiff.countByKind(deltas) }

    // The pair this class exists for, by
    // Note [Holding the scores is what makes a delta readable].
    oldElementFor { |delta| ^ScoreDiff.oldElementFor(delta, oldScore) }
    newElementFor { |delta| ^ScoreDiff.newElementFor(delta, newScore) }

    printOn { |stream|
        stream << "ScoreChange(" << deltas.size << " delta"
            << if (deltas.size == 1) { "" } { "s" };
        label !? { stream << ", " << label.asCompileString };
        stream << ")"
    }
}
