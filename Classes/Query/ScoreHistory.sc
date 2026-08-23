// ScoreHistory: where a score has been, as an append-only log of ScoreChange.
//
// A record of states, not a machine for moving between them. It stores each
// diff and keeps `ScoreDiff`'s boundary: no patch, revert or undo.
//
// Immutable. `recorded` answers a new history.


// Note [A log entry means something changed]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Recording a score identical to the current one answers the same
// history. An entry is a change, so `changeCount` counts edits.
//
// Recording on every keystroke costs one diff and nothing else.
ScoreHistory {
    var <initialScore, changeList;

    // >>> ScoreHistory.start(MusicScore.oneStaff(Measure("2/4", "c4 d4"))).changeCount
    // 0
    *start { |score|
        if (score.isKindOf(MusicScore).not) {
            Error("ScoreHistory.start: expected MusicScore, got %."
                .format(score.class)).throw
        };
        ^super.newCopyArgs(score, [])
    }

    *prWith { |score, list| ^super.newCopyArgs(score, list) }

    // `ScoreChange.between` owns score and label checks.
    recorded { |newScore, label|
        var change = ScoreChange.between(this.currentScore, newScore, label);
        if (change.changed.not) { ^this };
        ^ScoreHistory.prWith(initialScore, changeList ++ [change])
    }

    // >>> ScoreHistory.start(MusicScore.oneStaff(Measure("1/4", "c4")))
    //     .recorded(MusicScore.oneStaff(Measure("1/4", "d4"))).changed
    // true
    currentScore {
        ^if (changeList.isEmpty) { initialScore } { changeList.last.newScore }
    }

    // A copy, so holding the list can't edit the log.
    changes { ^changeList.copy }

    lastChange { ^changeList.last }
    changeCount { ^changeList.size }
    changed { ^changeList.notEmpty }

    // Every delta the log holds, oldest first. Flattened one level by
    // hand: a delta is a Dictionary, and `flat` would go on into it.
    deltas {
        ^changeList.inject([], { |all, change| all ++ change.deltas })
    }

    countByKind { ^ScoreDiff.countByKind(this.deltas) }

    printOn { |stream|
        stream << "ScoreHistory(" << changeList.size << " change"
            << if (changeList.size == 1) { "" } { "s" } << ")"
    }
}
