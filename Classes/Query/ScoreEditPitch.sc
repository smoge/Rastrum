// Note [A pitch edit is not a run edit]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `transposeBy` and `assignPitches` take selection rules, not run rules. A run
// edit rebuilds one container's children, so it needs one timeline, one
// contiguous group and one owner. A pitch edit leaves the shape in place. A
// selection scattered over two staves is still one pitch edit.
//
// The tie rule stays. Moving half a logical tie leaves one written sound on two
// pitches. `Validator` would only catch that later, inside a leaf the caller
// didn't name. Asking here names the selection.
//
// The pitch arithmetic is `ScoreSelection`'s, unchanged. This extension adds
// the edit boundary: check before, validate after.

+ ScoreEdit {

    // A copy of the tree with one selected chord spread into one note per pitch.
    //
    // A thin route over `reshapeRun`. This method only adds chord reading,
    // pitch order and the plain-source check.
    //
    // >>> { var bar = Measure("2/4", "<c e g>2");
    //     ScoreEdit.arpeggiateChord(bar, ScoreSelection(bar), "4 8 8")
    //     .leaves.collect { |leaf| leaf.pitch.letter } }.value
    // [ c, e, g ]
    // >>> { var bar = Measure("2/4", "<c e g>2");
    //     ScoreEdit.arpeggiateChord(bar, ScoreSelection(bar), "4 8 8", \descending)
    //     .leaves.collect { |leaf| leaf.pitch.letter } }.value
    // [ g, e, c ]
    //
    // ^ ScoreElement
    *arpeggiateChord { |element, selection, durations, order = \written|
        var label = "arpeggiateChord";
        var chord = this.prCheckedOneChord(element, selection, label);
        var wanted = this.prCheckedArpeggioDurations(durations,
            chord.pitches.size, label);

        ^this.reshapeRun(element, selection,
            this.prOrderedPitches(chord.pitches.asArray, order, label)
                .collect { |pitch, index| MusicNote(pitch, wanted[index]) })
    }

    // A copy of the tree with one selected note run gathered into one chord.
    //
    // The inverse of `arpeggiateChord`: same thin route and plain-source check.
    //
    // >>> { var bar = Measure("2/4", "c4 e8 g8");
    //     ScoreEdit.gatherNotes(bar, ScoreSelection(bar))
    //     .leaves.first.pitches.size }.value
    // 3
    //
    // ^ ScoreElement
    *gatherNotes { |element, selection|
        var label = "gatherNotes";
        var records = this.prCheckedNoteRun(element, selection, label);
        var pitches = records.collect { |record| record[\leaf].pitch };

        ^this.reshapeRun(element, selection, [Chord(pitches,
            this.prTotalOf(records.collect { |record| record[\written] }))])
    }

    // one plain chord leaf
    //
    // ^ Chord
    *prCheckedOneChord { |element, selection, label|
        var records;

        this.prCheckedSelectionOf(element, selection, label,
            "chord to arpeggiate");
        records = selection.records;
        if (records.size != 1) {
            Error(
                "ScoreEdit.%: the selection holds % leaves. Arpeggiate one chord at a time."
                .format(label, records.size)
            ).throw
        };
        if (records.first[\leaf].isKindOf(Chord).not) {
            Error(
                "ScoreEdit.%: % is a %, not a chord. There is nothing to spread out."
                .format(
                    label,
                    records.first[\path].asCompileString,
                    records.first[\leaf].class.name
                )
            ).throw
        };
        this.prCheckedPlain(records.first, label, "chord");
        ^records.first[\leaf]
    }

    // a run of plain notes, in written order
    //
    // ^ [IdentityDictionary]
    *prCheckedNoteRun { |element, selection, label|
        var records, seen = [];

        this.prCheckedSelectionOf(element, selection, label, "run to gather");
        records = selection.records;
        records.do { |record|
            var leaf = record[\leaf];
            if (leaf.isKindOf(MusicNote).not) {
                Error(
                    "ScoreEdit.%: % is a %, not a note. A chord is one note per pitch, so gather notes and nothing else."
                    .format(
                        label,
                        record[\path].asCompileString,
                        leaf.class.name
                    )
                ).throw
            };
            this.prCheckedPlain(record, label, "note");
            // Name the editing method before `Chord` refuses the repeat.
            if (seen.any { |pitch| pitch == leaf.pitch }) {
                Error(
                    "ScoreEdit.%: % is already in this run. A chord holds one of each pitch, so a unison needs two voices."
                    .format(label, leaf.pitch)
                ).throw
            };
            seen = seen.add(leaf.pitch)
        };
        ^records
    }

    // Refuse source facts this helper would otherwise have to place.
    //
    // ^ IdentityDictionary
    *prCheckedPlain { |record, label, saying|
        var leaf = record[\leaf];
        var carried = case
            { this.prTiesAnything(leaf) } { "a tie" }
            { leaf.markings.notEmpty } { "a marking" }
            { leaf.hasSpanners } { "a span endpoint" }
            { leaf.hasGraces } { "a grace group" }
            { true } { nil };

        carried !? {
            Error(
                "ScoreEdit.%: the % at % carries %. This helper writes plain material only, so detach or restate it first."
                .format(
                    label,
                    saying,
                    record[\path].asCompileString,
                    carried
                )
            ).throw
        };
        ^record
    }

    // written durations, one per pitch. `reshapeRun` owns the occupied space,
    // and `Validator` owns what a note head can spell.
    //
    // ^ [Duration]
    *prCheckedArpeggioDurations { |durations, wanted, label|
        var list;
        var failed = false;

        { list = Duration.asDurations(durations) }.try { |error| failed = true };
        if (failed) {
            Error(
                "ScoreEdit.%: % is not a list of durations."
                .format(label, durations.asCompileString)
            ).throw
        };
        if (list.size != wanted) {
            Error(
                "ScoreEdit.%: % durations for % chord pitches. Arpeggiation is one note per pitch."
                .format(label, list.size, wanted)
            ).throw
        };
        list.do { |each|
            if (each <= Duration(0, 1)) {
                Error(
                    "ScoreEdit.%: duration % is not positive. Every note in the spread takes time."
                    .format(label, each)
                ).throw
            }
        };
        ^list
    }

    // Written order or by height, with `cents` and written order behind it,
    // which is `ScoreSelection`'s rule and not a second one.
    //
    // ^ [MusicPitch]
    *prOrderedPitches { |pitches, order, label|
        var down;

        if (order == \written) { ^pitches };
        if ((order == \ascending).not and: { (order == \descending).not }) {
            Error(
                "ScoreEdit.%: % is not an order. Use \\written, \\ascending or \\descending."
                .format(label, order.asCompileString)
            ).throw
        };
        down = order == \descending;
        // Source index stays ascending even when height order descends.
        ^pitches.collect { |pitch, index| [pitch, index] }
            .sort { |a, b|
                case
                { a[0].height != b[0].height } {
                    if (down) { a[0].height > b[0].height }
                        { a[0].height < b[0].height } }
                { a[0].cents != b[0].cents } {
                    if (down) { a[0].cents > b[0].cents }
                        { a[0].cents < b[0].cents } }
                { true } { a[1] <= b[1] }
            }
            .collect { |each| each[0] }
    }

    // A copy of the tree with every selected leaf moved by an interval.
    //
    // See Note [A pitch edit is not a run edit].
    //
    // The pitch movement is `ScoreSelection#transposeBy`, which carries each
    // leaf's markings, spanners, ties and grace group across. Rests stay where
    // they are and their grace groups move with them.
    //
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.transposeBy(bar, ScoreSelection(bar),
    //     MusicInterval.named(\major, 3)).leaves.collect { |leaf|
    //     leaf.pitch.letter } }.value
    // [ e, f ]
    // >>> { var bar = Measure("4/4", "c4~ c4 d2");
    //     ScoreEdit.transposeBy(bar, ScoreSelection(bar),
    //     MusicInterval.named(\minor, 2)).leaves.collect { |leaf|
    //     leaf.pitch.letter } }.value
    // [ d, d, e ]
    //
    // ^ ScoreElement
    *transposeBy { |element, selection, interval|
        var label = "transposeBy";

        this.prCheckedSelectionOf(element, selection, label,
            "leaf to transpose");
        this.prCheckedInterval(interval, label);
        this.prCheckedWholeLogicalTies(element, selection.records, label);
        // `transposeBy` copies the source tree, so this validates once on the
        // answer exactly as the addressed methods do.
        ^Validator.validate(selection.transposeBy(interval))
    }

    // A number of semitones is the mistake worth naming: it reaches
    // `MusicInterval#transpose` as a receiver that does not understand it, so
    // the unchecked failure is a *transpose not understood* several layers in.
    //
    // ^ MusicInterval
    *prCheckedInterval { |interval, label|
        if (interval.isKindOf(MusicInterval).not) {
            Error(
                "ScoreEdit.%: expected a MusicInterval, got %.%"
                .format(
                    label,
                    if (interval.isNil) { "nil" } { interval.class.name },
                    if (interval.isNumber) {
                    " A count of semitones is not one, because it does not say "
                    "how to spell what it lands on. Use "
                    "MusicInterval.named(\\minor, 3), or MusicInterval.between "
                    "two pitches."
                    } { "" }
                )
            ).throw
        };
        ^interval
    }

    // A copy of the tree with each selected sounding note repitched.
    // See Note [A pitch edit is not a run edit].
    //
    // A checked `mapLogicalTies`: the function is asked once per logical tie,
    // rests are skipped, half ties are refused and the answer is validated.
    //
    // >>> { var bar = Measure("2/4", "c4 d4");
    //     ScoreEdit.rewritePitches(bar, ScoreSelection(bar), { 72 })
    //     .leaves.collect { |leaf| leaf.pitch.letter } }.value
    // [ c, c ]
    // >>> { var bar = Measure("4/4", "c4 r4 d4 r4");
    //     ScoreEdit.rewritePitches(bar, ScoreSelection(bar), { \g })
    //     .leaves.collect { |leaf| leaf.isKindOf(MusicRest) } }.value
    // [ false, true, false, true ]
    //
    // ^ ScoreElement
    *rewritePitches { |element, selection, function|
        var label = "rewritePitches";

        this.prCheckedSelectionOf(element, selection, label, "note to rewrite");
        // A pitch also answers `value`, so require the callback shape.
        if (function.isKindOf(Function).not) {
            Error(
                "ScoreEdit.%: expected a Function, got %. It is asked once per sounding note and answers the pitch that note becomes."
                .format(
                    label,
                    if (function.isNil) { "nil" }
                    { function.class.name }
                )
            ).throw
        };
        this.prCheckedWholeLogicalTies(element, selection.records, label);
        ^Validator.validate(selection.mapLogicalTies(function))
    }

    // Checked score-level route over `ScoreSelection#spellPitches`.
    //
    // >>> { var bar = Measure("4/4", "c4 c#4 d4 c#4");
    //     ScoreEdit.spellPitches(bar, ScoreSelection(bar), SpellingPolicy.byMotion)
    //     .leaves.collect { |leaf| leaf.pitch.spelling } }.value
    // [ c[4], c#[4], d[4], db[4] ]
    //
    // ^ ScoreElement
    *spellPitches { |element, selection, policy|
        var label = "spellPitches";

        this.prCheckedSelectionOf(element, selection, label, "note to spell");
        this.prCheckedSpellingPolicy(policy, label);
        this.prCheckedWholeLogicalTies(element, selection.records, label);
        // No sounding note: no history change.
        if (selection.logicalTies.isEmpty) { ^element };
        ^Validator.validate(selection.spellPitches(policy))
    }

    // Validate spelling before the silent no-op path.
    *prCheckedSpellingPolicy { |policy, label|
        var where = "ScoreEdit.%".format(label);

        MusicPitch.checkedSpelling(policy, where);
        ^SpellingPolicy.prCheckedPassageCapable(policy, where)
    }

    // A copy of the tree with a pitch row dealt over the selection.
    //
    // See Note [A pitch edit is not a run edit].
    //
    // The dealing is `ScoreSelection#assignPitches`: one pitch per *sounding
    // note* rather than per note head, so a tied run takes one between its
    // heads and a chord's pitches each take their own. Rests are skipped and
    // material shorter than the notes cycles.
    //
    // The row is dealt in onset order, so a selection covering two staves
    // alternates between them rather than filling one and then the other.
    // Narrow it with `inStaff` to deal one line at a time.
    //
    // A selection holding no sounding note answers the tree it was given.
    //
    // >>> { var bar = Measure("4/4", "c4 d4 e4 f4");
    //     ScoreEdit.assignPitches(bar, ScoreSelection(bar), "g a")
    //     .leaves.collect { |leaf| leaf.pitch.letter } }.value
    // [ g, a, g, a ]
    // >>> { var bar = Measure("4/4", "c4~ c4 d2");
    //     ScoreEdit.assignPitches(bar, ScoreSelection(bar), "g a")
    //     .leaves.collect { |leaf| leaf.pitch.letter } }.value
    // [ g, g, a ]
    // >>> { var bar = Measure("4/4", "r1"); ScoreEdit.assignPitches(bar, ScoreSelection(bar), "g") === bar }.value
    // true
    //
    // ^ ScoreElement
    *assignPitches { |element, selection, pitches|
        var label = "assignPitches", checkedPitches;

        this.prCheckedSelectionOf(element, selection, label, "leaf to repitch");
        checkedPitches = this.prCheckedPitchMaterial(pitches, label);
        this.prCheckedWholeLogicalTies(element, selection.records, label);
        // No sounding note: unchanged tree, so history records no change.
        if (selection.logicalTies.isEmpty) { ^element };
        ^Validator.validate(selection.assignPitches(checkedPitches))
    }

    // Empty and marked rows get this method's name.
    //
    // Pitch spelling still belongs to `MusicPitch`. Parsing here also guards
    // the no-op path: bad material is a bad call even over silence.
    *prCheckedPitchMaterial { |pitches, label|
        if (pitches.isKindOf(String) and: { pitches.includes($:) }) {
            Error(
                "ScoreEdit.%: \"%\" carries a marking. This edits pitch only; use addMarking or setMarking for marks."
                .format(label, pitches)
            ).throw
        };
        // A String is a sequenceable collection, so this reads "" and [] alike,
        // and leaves a lone midinote or note name to `MusicPitch`.
        if (pitches.isNil
            or: { pitches.isSequenceableCollection and: { pitches.isEmpty } }) {
            Error(
                "ScoreEdit.%: needs at least one pitch, e.g. \"c e g\"."
                .format(label)
            ).throw
        };
        ^MusicPitch.asPitches(pitches)
    }

}
