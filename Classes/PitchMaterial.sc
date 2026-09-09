// PitchMaterial: pitch-material rows spelled into leaves.
//
// Rows in, leaves out. No score construction or validation here.
//
// Material stays plain Arrays so it composes with `collect`, `flop`,
// `rotate` and the rest. `Duration` is only the exact carrier inside.
//
// A row is a list. `[[60], [64]]` is two notes and `[[60, 64]]` is
// one chord. One flat list could not tell those apart.
//
// A row atom is a pitch number or `(pitchNumber: 60, cents: 13.7)`.
// Not `[60, 13.7]`: an Array already means a row.

PitchMaterial {

    // One leaf per row: rest, note or chord. Function policies also
    // read the passage position.
    //
    // `dur` defaults inside. SuperCollider argument defaults must be literal.
    //
    // >>> PitchMaterial.leafRows([[], [60], [60, 64, 67]]).collect { |each| each.class.name }
    // [ MusicRest, MusicNote, Chord ]
    // >>> PitchMaterial.leafRows([[60]]).first.pitch.spelling           -> c[4]
    // >>> PitchMaterial.leafRows([[61]], \flats).first.pitch.spelling   -> db[4]
    // >>> PitchMaterial.leafRows([[MusicPitch.number(60, 1%/2)]]).first.pitch.spelling
    // c+[4]
    // >>> PitchMaterial.leafRows([[(pitchNumber: 60, cents: 13.7)]]).first.pitch.cents
    // 13.7
    // >>> PitchMaterial.leafRows([[60]]).first.pitch.cents   -> 0.0
    *leafRows { |rows, policy = \sharps, dur|
        var checked = this.prCheckedRows(rows);
        var value = Duration.asDuration(dur ?? { Duration.whole });
        var previous, index = 0;

        this.prCheckedPolicy(policy);

        ^checked.collect { |row, rowIndex|
            var pitches = row.collect { |atom, column|
                var pitch = this.prSpelled(atom, policy,
                    this.prPassage(checked, rowIndex, column, index, previous));
                index = index + 1;
                pitch
            };
            previous = if (row.size == 1) { pitches.first };
            this.prLeaf(pitches, value)
        }
    }

    // A row atom for one frequency: nearest written pitch number and
    // the residual it missed by. The Float is spent here and nowhere
    // later.
    //
    // Frequency in, atom out. No pitch, leaf, row or score is built,
    // so the answer goes straight into a row.
    //
    // Uses the written pitch grid. There is no local grid argument;
    // missed distance stays in `cents`.
    //
    // >>> PitchMaterial.atomFromFreq(440)[\pitchNumber]   -> 69%/1
    // >>> PitchMaterial.atomFromFreq(440)[\cents]         -> 0.0
    // >>> PitchMaterial.leafRows([[PitchMaterial.atomFromFreq(55 * 7)]], \minimal).first.pitch.cents.round(0.1)
    // 18.8
    *atomFromFreq { |freq|
        var midi = 69 + (12 * log2(this.prCheckedFreq(freq) / 440));
        var steps = MusicPitch.quarterStepsPerSemitone;
        var quarters = (midi * steps).round.asInteger;
        var number = if ((quarters % steps) == 0) { quarters div: steps }
            { quarters %/ steps };
        ^(pitchNumber: MusicPitch.number(number),
            cents: (midi - number.asFloat) * 100)
    }

    // A frequency is a positive finite number. Nothing else has a pitch.
    *prCheckedFreq { |freq|
        if (freq.isNumber.not) {
            Error(
                "PitchMaterial.atomFromFreq: % is not a number."
                .format(freq.asCompileString)
            ).throw
        };

        if ((freq.abs < inf).not) {
            Error(
                "PitchMaterial.atomFromFreq: % is not finite."
                .format(freq.asCompileString)
            ).throw
        };

        if (freq <= 0) {
            Error(
                "PitchMaterial.atomFromFreq: % is not a positive frequency."
                .format(freq.asCompileString)
            ).throw
        };

        ^freq
    }

    *prLeaf { |pitches, dur|
        if (pitches.isEmpty) { ^MusicRest(dur) };
        if (pitches.size == 1) { ^MusicNote(pitches.first, dur) };
        ^Chord(pitches, dur)
    }

    // Monophonic passage facts for policies. Chord rows get none.
    *prPassage { |rows, rowIndex, column, index, previous|
        var mono = rows[rowIndex].size == 1;
        var behind = if (mono) { previous };
        var ahead = if (mono) { this.prSoleAtom(rows, rowIndex + 1) };

        // Keep the lookahead number and residual together.
        ^(row: rowIndex, column: column, index: index,
            previousPitch: behind,
            nextPitchNumber: ahead !? { |each| MusicPitch.number(each[0]) },
            nextCents: ahead !? { |each| each[1] },
            direction: this.prDirection(rows[rowIndex][column], behind, ahead))
    }

    // The only row shape that can act as a melodic neighbor.
    *prSoleAtom { |rows, index|
        var row = rows[index];
        if (row.notNil and: { row.size == 1 }) { ^row.first };
        ^nil
    }

    // Motion arriving or leaving for the first note.
    *prDirection { |atom, previous, next|
        if (previous.notNil) {
            ^this.prMotion(previous.height, previous.cents, atom[0], atom[1])
        };
        if (next.notNil) { ^this.prMotion(atom[0], atom[1], next[0], next[1]) };
        ^nil
    }

    // Written ladder first, residual second.
    *prMotion { |fromNumber, fromCents, toNumber, toCents|
        if (toNumber > fromNumber) { ^\up   };
        if (toNumber < fromNumber) { ^\down };
        if (toCents > fromCents)   { ^\up   };
        if (toCents < fromCents)   { ^\down };
        ^\same
    }

    // Check rows before passage facts read neighbors.
    *prCheckedRows { |rows|
        if (this.prIsRow(rows).not) { this.prRefuseRows(rows, nil) };
        ^rows.collect { |row, rowIndex|
            if (this.prIsRow(row).not) { this.prRefuseRows(row, rowIndex) };
            row.collect { |atom, column|
                this.prCheckedAtom(atom, rowIndex, column)
            }
        }
    }

    // Normalize to `[pitch number, cents]`. The Event vocabulary is closed.
    *prCheckedAtom { |value, rowIndex, column|
        var extra;
        if (value.isKindOf(Event).not) {
            ^[this.prCheckedNumber(value, rowIndex, column), 0.0]
        };
        extra = value.keys.asArray
            .reject { |key| #[\pitchNumber, \cents].includes(key) }.sort;
        if (extra.notEmpty) {
            this.prRefuseAt(rowIndex, column,
                "atom keys must be pitchNumber and cents, not %.".format(extra.join(", ")))
        };
        if (value[\pitchNumber].isNil) {
            this.prRefuseAt(rowIndex, column, "atom needs pitchNumber.")
        };
        ^[this.prCheckedNumber(value[\pitchNumber], rowIndex, column),
            this.prCheckedCents(value[\cents] ? 0, rowIndex, column)]
    }

    *prIsRow { |value|
        ^value.isSequenceableCollection and: { value.isKindOf(String).not }
    }

    *prRefuseRows { |value, rowIndex|
        var what = if (rowIndex.isNil) { "the argument" } { "row " ++ rowIndex };
        Error(
            "PitchMaterial: % is %, not a row. Use rows like [[60], [64]] or [[60, 64]]."
            .format(what, value.asCompileString)
        ).throw
    }

    *prCheckedNumber { |number, rowIndex, column|
        var exact, failure;
        // Catch here to add row and column.
        try { exact = MusicPitch.prCheckedExact(number, "a pitch number") }
            { |err| failure = err.what };
        if (failure.notNil) { this.prRefuseAt(rowIndex, column, failure) };
        ^exact
    }

    *prCheckedCents { |value, rowIndex, column|
        var cents, failure;
        try { cents = MusicPitch.checkedCents(value) } { |err| failure = err.what };
        if (failure.notNil) { this.prRefuseAt(rowIndex, column, failure) };
        ^cents
    }

    // `leafRows` is incremental: no whole-passage policies, and bad
    // spellings are refused before rows are spelled.
    //
    // >>> try { PitchMaterial.leafRows([[60]], SpellingPolicy.prWholePassage) } { |e| e.what.contains("whole passage") }
    // true
    // >>> try { PitchMaterial.leafRows([[]], \tonal) } { |e| e.what.contains("not a spelling") }
    // true
    *prCheckedPolicy { |policy|
        var label = "PitchMaterial.leafRows";

        MusicPitch.checkedSpelling(policy, label);
        ^SpellingPolicy.prCheckedIncremental(policy, label,
            "leafRows spells one row at a time.")
    }

    *prSpelled { |atom, policy, passage|
        var pitch, failure;

        try {
            pitch = MusicPitch.prFromNumberInPassage(atom[0], policy, atom[1], passage)
        } { |err| failure = err.what };

        if (failure.notNil) {
            this.prRefuseAt(passage[\row], passage[\column], failure)
        };

        ^pitch
    }

    // Name the caller's material address.
    *prRefuseAt { |rowIndex, column, failure|
        Error(
            "PitchMaterial: row %, column %: %".format(rowIndex, column, failure)
        ).throw
    }
}
