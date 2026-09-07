// ScoreHistory: immutable recorded score history with a cursor
//
// Undo and redo move through recorded scores. They do not invert deltas, so
// `ScoreDiff` stays observational.

// Equal scores are not changes. Recording after undo starts a new branch.
ScoreHistory {
    // initialScore: MusicScore, changeList: [ScoreChange], cursor: Integer
    var <initialScore, changeList, cursor;

    // >>> ScoreHistory.start(MusicScore.oneStaff(Measure("2/4", "c4 d4"))).changeCount
    // 0
    //
    // ^ Self
    *start { |score|
        if (score.isKindOf(MusicScore).not) {
            Error(
                "ScoreHistory.start: expected MusicScore, got %."
                .format(score.class)
            ).throw
        };
        ^super.newCopyArgs(score, [], 0)
    }

    // ^ Self
    *prWith { |score, list, at| ^super.newCopyArgs(score, list, at) }

    // `ScoreChange.between` owns score and label checks
    //
    // ^ Self
    recorded { |newScore, label|
        var change = ScoreChange.between(this.currentScore, newScore, label);
        if (change.changed.not) { ^this };
        ^ScoreHistory.prWith(initialScore,
            changeList.keep(cursor) ++ [change], cursor + 1)
    }

    // Run the function on `currentScore` and record the score it answers. The
    // label is checked first; the function may have side effects. Callback and
    // result checks stay here so refusals name this method.
    //
    // >>> ScoreHistory.start(MusicScore.oneStaff(Measure("1/4", "c4")))
    //     .edited("up", { MusicScore.oneStaff(Measure("1/4", "d4")) }).changeCount
    // 1
    //
    // ^ Self
    edited { |label, function|
        var answered;
        ScoreChange.checkedLabel(label);
        // Any object answers `value`, so a score passed here would record and
        // quietly make this `recorded` under another name.
        if (function.isKindOf(Function).not) {
            Error(
                "ScoreHistory.edited%: expected a Function, got %.%"
                .format(
                    this.prSaid(label),
                    function.class,
                    if (function.isKindOf(MusicScore)) {
                    " A score already built is `recorded`."
                    } { "" }
                )
            ).throw
        };
        answered = function.value(this.currentScore);
        if (answered.isKindOf(MusicScore).not) {
            Error(
                "ScoreHistory.edited%: expected the function to answer a MusicScore, got %."
                .format(this.prSaid(label), answered.class)
            ).throw
        };
        ^this.recorded(answered, label)
    }

    // ^ String
    prSaid { |label| ^if (label.isNil) { "" } { " " ++ label.asCompileString } }

    // >>> ScoreHistory.start(MusicScore.oneStaff(Measure("1/4", "c4")))
    //     .recorded(MusicScore.oneStaff(Measure("1/4", "d4"))).changed
    // true
    //
    // ^ MusicScore
    currentScore {
        ^if (cursor == 0) { initialScore } { changeList[cursor - 1].newScore }
    }

    // ^ Boolean
    canUndo { ^cursor > 0 }
    // ^ Boolean
    canRedo { ^cursor < changeList.size }

    // Unavailable undo/redo answers the same history.
    //
    // >>> ScoreHistory.start(MusicScore.oneStaff(Measure("1/4", "c4"))).undo.canUndo
    // false
    //
    // ^ Self
    undo {
        if (this.canUndo.not) { ^this };
        ^ScoreHistory.prWith(initialScore, changeList, cursor - 1)
    }

    // ^ Self
    redo {
        if (this.canRedo.not) { ^this };
        ^ScoreHistory.prWith(initialScore, changeList, cursor + 1)
    }

    // Applied changes up to the cursor, copied.
    //
    // ^ [ScoreChange]
    changes { ^changeList.keep(cursor) }

    // ^ Integer
    redoCount { ^changeList.size - cursor }

    // ^ ScoreChange | Nil
    lastChange { ^if (cursor == 0) { nil } { changeList[cursor - 1] } }
    // ^ Integer
    changeCount { ^cursor }
    // ^ Boolean
    changed { ^cursor > 0 }

    // Every delta of the applied branch, oldest first. Flattened one
    // level by hand: a delta is a Dictionary and `flat` would go on into it.
    //
    // ^ [IdentityDictionary]
    deltas {
        ^this.changes.inject([], { |all, change| all ++ change.deltas })
    }

    // ^ Dictionary
    countByKind { ^ScoreDiff.countByKind(this.deltas) }

    // The applied count plus the redo tail, where there is one.
    //
    // ^ Self
    printOn { |stream|
        stream << "ScoreHistory(" << cursor << " change"
            << if (cursor == 1) { "" } { "s" };
        if (this.canRedo) { stream << ", " << this.redoCount << " undone" };
        stream << ")"
    }
}
