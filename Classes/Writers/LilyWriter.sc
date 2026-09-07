// LilyWriter: LilyPond output.
//
// This file spells LilyPond music. `LilyProfile` owns document
// policy. `Rastrum.render` engraves the result.
LilyWriter : ScoreWriter {
    classvar <alterationNames, <articulationMarks, <dynamicMarks,
        <technicalMarks;

    // Whole-bar rests in the measure being written.
    var measureRests, includeMidi, pendingDirections;

    // Most directions stand before a leaf. `\startTextSpan` is a
    // post-event, attached to the note it starts on.
    //
    // Ramp endpoints are keyed by leaf, including bar-start
    // endpoints. Leaves write the bound-details override before and
    // the marker after.

    var pendingRamps;

    // Derived tempo steps for the whole score and which staff carries
    // them.
    //
    // See Note [The MIDI lane is one staff]
    var laneRecords, staffNumber;

    // Open tempo ramps by id, for the staff being written.
    // Structural, independent of MIDI: a half span is a
    // `\\startTextSpan` LilyPond closes where it likes, whether or
    // not anything asked for a tempo lane.
    var openRamps;
    var glissandoSkips, glissandoTieHidden;

    // Clef and meter in force in this staff. `restoreMeter` marks a
    // partial measure that shortened `Timing.measureLength`.
    var currentClef, currentMeter, restoreMeter = false;

    // Which engraving policy the document carries, always a
    // `LilyProfile`.
    //
    // See Note [A layout is the writer's, not the model's].
    var profile;

    // Leaf -> beam rows, for mixed groups only.
    //
    // LilyPond can infer uniform groups.
    var beamCounts;

    // Note [A dynamic is pinned, not interpolated]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Dynamics go through a table, as articulations do. A new model
    // word must choose a LilyPond spelling here. `TestMarking` keeps
    // this table complete against `Marking.dynamics`.

    *initClass {
        // Index = alterationSteps + 4, i.e. -2 .. +2 semitones by quarter tones.
        alterationNames = ["eses", "eseh", "es", "eh", "", "ih", "is", "isih", "isis"];
        articulationMarks = IdentityDictionary[
            \staccato      -> "-.",
            \staccatissimo -> "-!",
            \tenuto        -> "--",
            \accent        -> "->",
            \marcato       -> "-^",
            \portato       -> "\\portato",
            \fermata       -> "\\fermata",
            \breath        -> "\\breathe",
            \caesura       -> "\\caesura"
        ];
        // LilyPond writes technical marks as post-events too.
        //
        // See Note [A technical mark is not an articulation] in Marking.sc.
        technicalMarks = IdentityDictionary[
            \upbow -> "\\upbow", \downbow -> "\\downbow",
            // LilyPond wants this one flat: `\snapPizzicato` is an
            // unknown command there, so the model word and the
            // spelling differ.
            \stopped -> "\\stopped", \snapPizzicato -> "\\snappizzicato",
            // `\open` is the open-string glyph here.
            //
            // MusicXML splits that from the mute circle.
            \openString -> "\\open",
            // `\flageolet` is the circle mark. `\harmonic` is a diamond head.
            \harmonic -> "\\flageolet"
        ];
        // See Note [A dynamic is pinned, not interpolated].
        dynamicMarks = IdentityDictionary[
            \ppppp -> "ppppp", \pppp -> "pppp", \ppp -> "ppp", \pp -> "pp",
            \p -> "p", \mp -> "mp", \mf -> "mf", \f -> "f", \ff -> "ff",
            \fff -> "fff", \ffff -> "ffff", \fffff -> "fffff"
        ];
    }


    // Note [A layout is the writer's, not the model's]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Layout is writer policy, not a score fact. `LilyProfile` owns
    // it. `midi` writes `\midi { }` beside the layout. `layout` takes
    // a name or a `LilyProfile`.
    //
    // >>> LilyWriter.new(layout: \complexRhythm).layout   -> complexRhythm
    // >>> try { LilyWriter.new(layout: \spacious) } { \refused }
    // refused


    *new { |midi = true, layout = \default| ^super.new.init(midi, layout) }

    // A tempo ramp left open when the music ends is as broken as one
    // left open inside a staff, so this writer checks the whole
    // document.
    write { |element|
        var result;
        this.prRequireBeamableGroups(element);
        this.prRequireTiesReachTheirPitch(element);
        this.prRequireGlissandiReachOneAttack(element);
        result = super.write(element);
        this.prRequireNoOpenRampsAnywhere("the music ends");
        ^result
    }

    init { |midi = true, argLayout = \default|
        includeMidi = midi;
        profile = LilyProfile.from(argLayout);
        ^this
    }

    // The name, so a writer built from either spelling answers the
    // same way.
    layout { ^profile.name }
    profile { ^profile }

    *layouts { ^LilyProfile.names }

    // Note [The MIDI lane is one staff]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // The hidden tempo step lane goes in the first staff only.
    //
    // Only derived steps go there.
    //
    // Written tempo marks are already visible.

    prepare { |element|
        glissandoSkips = IdentitySet.new;
        glissandoTieHidden = IdentitySet.new;
        this.prFindGlissandoSkips(element);
        measureRests = IdentitySet.new;
        pendingDirections = IdentityDictionary.new;
        pendingRamps = IdentityDictionary.new;
        openRamps = Dictionary.new;
        staffNumber = 0;
        laneRecords = if (includeMidi) {
            PlaybackTempoMap.scoreLaneRecords(element)
                .select { |record| record[\derived] == true }
        } { [] };
        currentClef = nil;
        currentMeter = nil;
        restoreMeter = false;
        beamCounts = this.class.prBeamCounts(element);
        ^this
    }

    // LilyPond can infer secondary beams and hooks, but mixed groups use the
    // same `AutoBeam.rowsFor` rows as MusicXML.
    //
    // Uniform groups write only `[ ... ]`.


    *prBeamCounts { |element|
        var out = IdentityDictionary.new;
        AutoBeam.groupsIn(element).do { |group|
            var flags = group.collect { |leaf| leaf.dur.flags ? 0 };
            if (flags.asSet.size > 1) {
                AutoBeam.rowsFor(group).do { |rows, i| out[group[i]] = rows }
            }
        };
        ^out
    }

    // Beam strokes meeting each side of this stem.
    prBeamCountString { |leaf|
        var rows = beamCounts[leaf];
        var left = 0, right = 0;
        if (rows.isNil) { ^"" };
        rows.do { |row|
            if ([\end, \continue, \backwardHook].includes(row[1])) {
                left = left + 1
            };
            if ([\begin, \continue, \forwardHook].includes(row[1])) {
                right = right + 1
            };
        };
        ^"\\set stemLeftBeamCount = #% \\set stemRightBeamCount = #% "
            .format(left, right)
    }

    // Prose inside LilyPond quotes: markup text and staff names.
    //
    // >>> LilyWriter.markupString("a \\ b").asCompileString   -> "a \\\\ b"
    // >>> LilyWriter.markupString("say \"hi\"").asCompileString
    // "say \\\"hi\\\""
    *markupString { |text|
        ^text.asString.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    // LilyPond has `\sfz`.
    //
    // Other sforzando spellings use `make-dynamic-script`.
    //
    // A paired dynamic joins the same glyph.
    //
    // See Note [One mark at one moment] in Writers/ScoreWriter.sc.
    //
    // >>> LilyWriter.sforzandoString(MN("c4:sfz"))        -> \sfz
    // >>> LilyWriter.sforzandoString(MN("c4:sffz"))       -> -#(make-dynamic-script "sffz")
    // >>> LilyWriter.sforzandoString(MN("c4")).isEmpty   -> true
    // The one place a dynamic word is read.
    // See Note [A dynamic is pinned, not interpolated].
    //
    // >>> LilyWriter.dynamicMark(\mf)   -> mf
    *dynamicMark { |value|
        ^dynamicMarks[value] ?? {
            Error("LilyWriter: no mark for dynamic %".format(value)).throw }
    }

    *sforzandoString { |leaf|
        var marking = this.sforzandoOf(leaf);
        var word, dynamic;
        if (marking.isNil) { ^"" };
        word = Marking.sforzandoSpelling(marking.value);
        dynamic = this.dynamicOf(leaf);
        dynamic !? { word = word ++ "/" ++ this.dynamicMark(dynamic.value) };
        if (word == "sfz") { ^"\\sfz" };
        ^"-#(make-dynamic-script \"" ++ word ++ "\")"
    }

    // Post-events in LilyPond order: articulations, dynamic, text, tie.
    //
    // `^` and `_` place post-events above or below.
    *markingString { |leaf|
        var out = "";
        leaf.articulations.do { |marking|
            out = out ++ (articulationMarks[marking.value]
                ?? { Error("LilyWriter: no mark for articulation %".format(
                    marking.value)).throw })
        };
        leaf.technicals.do { |marking|
            out = out ++ (technicalMarks[marking.value]
                ?? { Error("LilyWriter: no mark for technical %".format(
                    marking.value)).throw })
        };
        // A sforzando plus dynamic is one glyph, `sfz/p`.
        out = out ++ this.sforzandoString(leaf);
        if (leaf.sforzandos.isEmpty) {
            this.dynamicOf(leaf) !? { |marking|
                out = out ++ "\\" ++ this.dynamicMark(marking.value) };
        };
        leaf.texts.do { |marking|
            out = out
                ++ if (marking.placement == \above) { "^" } { "_" }
                ++ "\\markup { \"" ++ this.markupString(marking.value) ++ "\" }"
        };
        // A stop closes before a new start opens on the same note.
        leaf.spannerStops.do { |endpoint| out = out ++ this.spannerString(endpoint) };
        leaf.spannerStarts.do { |endpoint| out = out ++ this.spannerString(endpoint) };
        ^out
    }

    // TextSpanner text is a grob override, so it must precede the start note.
    *spannerPrefix { |leaf|
        var out = "";
        leaf.spannerStarts.do { |endpoint|
            if (endpoint.isText) {
                out = out ++ "\\once \\override TextSpanner.bound-details.left.text "
                    ++ "= \\markup { \"" ++ this.markupString(endpoint.text) ++ "\" } "
            }
        };
        ^out
    }

    // The grace group before the host leaf.
    //
    // Choose the LilyPond command from model vocabulary.
    //
    // Emit before `spannerPrefix`, so `\once` binds to the host.
    *graceString { |leaf|
        var command;
        if (leaf.hasGraces.not) { ^"" };
        command = if (leaf.graceStyle == \acciaccatura) {
            "\\acciaccatura"
        } {
            "\\grace"
        };
        ^command ++ " { " ++ leaf.graces.collect { |grace|
            if (grace.isKindOf(Chord)) {
                "<" ++ grace.pitches.collect { |p| this.pitchString(p) }.join(" ")
                    ++ ">" ++ this.durationString(grace.dur)
            } {
                this.pitchString(grace.pitch) ++ this.durationString(grace.dur)
            }
        }.join(" ") ++ " } "
    }


    // Note [Two spellings for one grouped meter]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // A grouped meter prints additively. LilyPond changed the syntax at 2.25.34:
    // older files need `\\compoundMeter`, newer ones use complex `\\time`.
    //
    // The two engrave identically.
    //
    // >>> LilyWriter.meterString(Meter(4, 4)).asCompileString
    // "\\time 4/4"


    *meterString { |meter|
        var groups;
        if (meter.isGrouped.not) {
            ^"\\time " ++ meter.count ++ "/" ++ meter.unit
        };
        groups = meter.groups.join(" ");
        if (LilyWriter.speaksComplexTime) {
            ^"\\time #'((" ++ groups ++ ") . " ++ meter.unit ++ ")"
        };
        ^"\\compoundMeter #'((" ++ groups ++ " " ++ meter.unit ++ "))"
    }

    // Whether the declared LilyPond understands complex `\\time`.
    *speaksComplexTime {
        ^LilyWriter.prVersionAtLeast(Rastrum.lilypondVersion, [2, 25, 34])
    }

    *prVersionAtLeast { |version, wanted|
        var parts = version.asString.split($.).collect { |piece| piece.asInteger };
        wanted.do { |want, i|
            var got = parts[i] ? 0;
            if (got != want) { ^(got > want) }
        };
        ^true
    }

    // One post-event per endpoint, by kind.
    //
    // Slurs can be numbered. Hairpins cannot.
    *spannerString { |endpoint|
        var mark;
        if (endpoint.isText) {
            ^if (endpoint.isStart) {
                if (endpoint.placement == \above) { "^" } { "_" } ++ "\\startTextSpan"
            } {
                "\\stopTextSpan"
            }
        };
        if (endpoint.isHairpin) {
            ^if (endpoint.isStart) {
                if (endpoint.direction == \crescendo) { "\\<" } { "\\>" }
            } {
                "\\!"
            }
        };
        // Beams are unnumbered too.
        if (endpoint.isBeam) {
            ^if (endpoint.isStart) { "[" } { "]" }
        };
        // `\glissando` is a post-event on the note the line leaves,
        // so the stop end spells nothing. LilyPond pairs chord
        // noteheads by position, which is the pairing the model
        // guarantees.
        if (endpoint.isGlissando) {
            ^if (endpoint.isStart) { "\\glissando" } { "" }
        };
        if (endpoint.isSlur.not) {
            Error("LilyWriter: no spelling for a % spanner".format(endpoint.kind)).throw
        };
        mark = if (endpoint.isStart) { "(" } { ")" };
        ^if (endpoint.id == 1) { mark } { "\\=" ++ endpoint.id ++ mark }
    }

    // The undotted value as LilyPond spells it, plus one `.` per dot.
    // Compare MusicXMLWriter.typeString, which spells the same
    // `Duration#notation` pair its own way. That pair is all the
    // model says.
    //
    // >>> LilyWriter.durationString(Duration(3, 8)).asCompileString  -> "4."
    // >>> LilyWriter.durationString(Duration(7, 16)).asCompileString -> "4.."
    // >>> LilyWriter.durationString(Duration(2, 1)).asCompileString
    // "\\breve"
    *durationString { |dur|
        var pair = dur.notation, value, dots;
        if (pair.isNil) {
            Error(
                "LilyWriter: % is not notatable as one leaf. Prepare the tree before "
                "writing."
                .format(dur)
            ).throw
        };
        value = pair[0];
        dots = pair[1];
        ^if (value.denominator == 1) {
            switch(value.numerator,
                1, { "1" }, 2, { "\\breve" }, 4, { "\\longa" },
                { Error("LilyWriter: no note value for %".format(value)).throw })
        } {
            value.denominator.asString
        } ++ String.fill(dots, { $. })
    }

    // Letter, alteration, then `'` per octave above the third, c' is middle C.
    //
    // >>> LilyWriter.pitchString(MusicPitch(\c, \sharp, 4)).asCompileString
    // "cis'"
    // >>> LilyWriter.pitchString(MusicPitch(\e, \flat, 3)).asCompileString
    // "ees"
    // >>> LilyWriter.pitchString(MusicPitch(\c, \quarterSharp, 5)).asCompileString   -> "cih''"
    *pitchString { |pitch|
        var index = pitch.alterationSteps + 4;
        var marks = pitch.octave - 3;  // LilyPond: c' is middle C
        if (index < 0 or: { index >= alterationNames.size }) {
            Error("LilyWriter: alteration % out of range".format(pitch.alter)).throw
        };
        ^pitch.letter.asString
            ++ alterationNames[index]
            ++ if (marks >= 0) {
                String.fill(marks, { RastrumChar.singleQuote })
            } {
                String.fill(marks.neg, { Char.comma })
            }
    }

    // LilyPond marks a tie once, on the note it leaves from.
    visitNote { |note|
        this.prWriteDirectionsAt(note);
        stream << LilyWriter.graceString(note);
        stream << LilyWriter.spannerPrefix(note);
        stream << this.prRampPrefixAt(note);
        stream << this.prBeamCountString(note);
        stream << this.prGlissandoSkipPrefix(note);
        stream << LilyWriter.pitchString(note.pitch)
               << LilyWriter.durationString(note.dur)
               << LilyWriter.markingString(note)
               << this.prRampMarksAt(note);
        if (note.tiesToNext and: { this.prTieSuppressed(note).not }) {
            stream << "~"
        };
        stream << " ";
    }

    // `R` is LilyPond's whole-bar rest. The bar decides which rests are those.
    visitRest { |rest|
        this.prWriteDirectionsAt(rest);
        stream << LilyWriter.graceString(rest);
        stream << LilyWriter.spannerPrefix(rest);
        stream << this.prRampPrefixAt(rest);
        if (measureRests.notNil and: { measureRests.includes(rest) }) {
            stream << "R" << LilyWriter.spanString(rest.dur)
                   << this.prRampMarksAt(rest) << " ";
            ^this
        };
        stream << "r" << LilyWriter.durationString(rest.dur)
               << LilyWriter.markingString(rest)
               << this.prRampMarksAt(rest) << " ";
    }

    // Whole chord ties follow the chord. Partial ties follow noteheads.
    visitChord { |chord|
        var whole;
        this.prRequireNonEmptyChord(chord);
        whole = chord.tiesAll;
        this.prWriteDirectionsAt(chord);
        stream << LilyWriter.graceString(chord);
        stream << LilyWriter.spannerPrefix(chord);
        stream << this.prRampPrefixAt(chord);
        stream << this.prBeamCountString(chord);
        stream << this.prGlissandoSkipPrefix(chord);
        stream << "<"
            << chord.pitches.collect { |p, i|
                LilyWriter.pitchString(p)
                    ++ if (whole.not and: { chord.tiesToNext[i] }) { "~" } { "" }
            }.join(" ")
            << ">" << LilyWriter.durationString(chord.dur)
            << LilyWriter.markingString(chord)
            << this.prRampMarksAt(chord);
        if (whole and: { this.prTieSuppressed(chord).not }) { stream << "~" };
        stream << " ";
    }

    visitTuplet { |tuplet|
        this.prRequireNonEmptyTuplet(tuplet);
        if (tuplet.isTrivial) { ^this.writeChildren(tuplet) };
        stream << "\\tuplet " << tuplet.actualNotes << "/" << tuplet.normalNotes << " { ";
        this.writeChildren(tuplet);
        stream << "} ";
    }

    // A duration after `\partial` or `R`. Non-notehead spans use
    // scaled form, such as `1*5/8`.
    *spanString { |span|
        ^if (span.isNotatable) {
            LilyWriter.durationString(span)
        } {
            "1*" ++ span.numerator ++ "/" ++ span.denominator
        }
    }

    // LilyPond has two short-bar forms: `\partial` and
    // `Timing.measureLength`.
    *partialDirective { |measure|
        if (measure.isAnacrusis) {
            ^"\\partial " ++ LilyWriter.spanString(measure.barDuration) ++ " "
        };
        ^"\\set Timing.measureLength = " ++
            LilyWriter.measureLengthValue(measure.barDuration) ++ " "
    }

    // `Timing.measureLength` took a Moment until LilyPond 2.25.34 and takes an
    // exact rational after it. The gate reads LilyPond output for this case.
    //
    // Same 2.25.34 boundary.
    // See Note [Two spellings for one grouped meter].
    //
    // >>> LilyWriter.measureLengthValue(Duration(5, 8))   -> #5/8
    *measureLengthValue { |span|
        if (LilyWriter.speaksRationalMeasureLength) {
            ^"#%/%".format(span.numerator, span.denominator)
        };
        ^"#(ly:make-moment %/%)".format(span.numerator, span.denominator)
    }

    *speaksRationalMeasureLength {
        ^LilyWriter.prVersionAtLeast(Rastrum.lilypondVersion, [2, 25, 34])
    }

    // Tempo can carry prose, a metronome mark or both. Rehearsal
    // marks and system text both become `\mark`.
    //
    // >>> LilyWriter.directionString(Direction.tempo("Allegro", beat: "4", bpm: 120)).asCompileString
    // "\\tempo \"Allegro\" 4 = 120 "
    // >>> LilyWriter.directionString(Direction.rehearsalMark("A")).asCompileString
    // "\\mark \\markup { \"A\" } "
    // >>> LilyWriter.directionString(Direction.text("solo")).asCompileString
    // "\\mark \\markup { \"solo\" } "
    // Tempo ramps are spans, not point directions.
    *writesTempoRamps { ^true }

    *directionString { |direction|
        var out;
        if (direction.isTempoRamp) {
            Error(
                "LilyWriter: tempo ramp endpoints are spans, not point directions."
            ).throw
        };
        this.prRequireWritableDirection(direction);
        if (direction.isTempo) {
            out = "\\tempo";
            direction.text !? { |text|
                out = out ++ " \"" ++ this.markupString(text) ++ "\""
            };
            if (direction.hasMetronome) {
                out = out ++ " " ++ this.durationString(direction.unit)
                    ++ " = " ++ direction.perMinute
            };
            ^out ++ " "
        };
        ^"\\mark \\markup { \"" ++ this.markupString(direction.text) ++ "\" } "
    }

    // Pair ramp endpoints as `ScoreWriter` places them.
    //
    // Raw writers check open ramps even when no MIDI lane is asked for.
    prTrackRamp { |endpoint|
        if (endpoint.isRampStart) {
            if (openRamps.notEmpty) {
                Error(
                    "LilyWriter: a tempo ramp is already open when the ramp with id % "
                    "starts. Close the first ramp before opening another."
                    .format(endpoint.id)
                ).throw
            };
            openRamps[endpoint.id] = endpoint;
            ^this
        };
        if (openRamps.includesKey(endpoint.id).not) {
            Error(
                "LilyWriter: a tempo ramp stop with id % closes nothing."
                .format(endpoint.id)
            ).throw
        };
        openRamps.removeAt(endpoint.id);
        ^this
    }

    // LilyPond writes a tie from the leaf it leaves, so the next
    // leaf in the same voice has to carry the pitch.
    prRequireTiesReachTheirPitch { |element|
        AutoBeam.timelinesIn(element).do { |leaves|
            var pending = [];
            leaves.do { |leaf|
                var arriving = this.prPitchesCarried(leaf);
                pending.do { |pitch|
                    if (arriving.any { |each| each == pitch }.not) {
                        Error(
                            "LilyWriter: % ties to the next leaf, but % does not carry "
                            "that pitch."
                            .format(pitch, leaf)
                        ).throw
                    }
                };
                pending = this.prPitchesTiedOnward(leaf);
            };
            if (pending.notEmpty) {
                Error(
                    "LilyWriter: % ties onward, but the voice ends."
                    .format(pending)
                ).throw
            };
        };
        ^this
    }

    prPitchesCarried { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.pitches };
        if (leaf.isKindOf(MusicNote)) { ^[leaf.pitch] };
        ^[]
    }

    prPitchesTiedOnward { |leaf|
        if (leaf.isKindOf(Chord)) { ^leaf.tiedPitches };
        if (leaf.isKindOf(MusicNote)) {
            ^if (leaf.tiesToNext) { [leaf.pitch] } { [] }
        };
        ^[]
    }

    // `\glissando` reaches the next note column. Tied continuations inside the
    // line get `NoteColumn.glissando-skip`, a hidden notehead and no visible tie.

    // Use `while`: `(a..b)` descends when the ends cross.
    prFindGlissandoSkips { |element|
        AutoBeam.timelinesIn(element).do { |leaves|
            var openAt = nil;
            leaves.do { |leaf, at|
                var marks = leaf.spanners.select { |each| each.isGlissando };
                if (marks.any { |each| each.isStop }) {
                    if (openAt.notNil) {
                        var mid = openAt + 1;
                        while { mid < at } {
                            glissandoSkips.add(leaves[mid]);
                            // Drop the tie into the hidden leaf.
                            glissandoTieHidden.add(leaves[mid - 1]);
                            mid = mid + 1;
                        }
                    };
                    openAt = nil;
                };
                if (marks.any { |each| each.isStart }) { openAt = at };
            }
        };
        ^this
    }

    prGlissandoSkipPrefix { |leaf|
        if (glissandoSkips.isNil or: { glissandoSkips.includes(leaf).not }) {
            ^""
        };
        ^"\\once \\override NoteColumn.glissando-skip = ##t "
            "\\once \\hide NoteHead "
    }

    prTieSuppressed { |leaf|
        ^glissandoTieHidden.notNil and: { glissandoTieHidden.includes(leaf) }
    }

    // Raw entry repeats the shared attack rule.
    prRequireGlissandiReachOneAttack { |element|
        AutoBeam.timelinesIn(element).do { |leaves|
            var openId = nil, openAt = nil;
            leaves.do { |leaf, at|
                var marks = leaf.spanners.select { |each| each.isGlissando };
                var stops = marks.select { |each| each.isStop };
                var starts = marks.select { |each| each.isStart };
                if (marks.notEmpty and: { leaf.isKindOf(MusicRest) }) {
                    Error(
                        "LilyWriter: % carries a glissando, but rests have no pitch."
                        .format(leaf)
                    ).throw
                };
                stops.do { |endpoint|
                    if (openId.isNil) {
                        Error(
                            "LilyWriter: glissando stop on % has no open start."
                            .format(leaf)
                        ).throw
                    };
                    if (endpoint.id != openId) {
                        Error(
                            "LilyWriter: glissando stop id % on % closes no open start, id % is open."
                            .format(endpoint.id, leaf, openId)
                        ).throw
                    };
                    this.prRequireOneAttackBetween(leaves, openAt, at);
                    this.prRequireEqualGlissandoWidth(leaves[openAt], leaf);
                    openId = nil; openAt = nil;
                };
                // See Note [Kinds that cannot overlap] in Spanner.sc.
                starts.do { |endpoint|
                    if (openId.notNil) {
                        Error(
                            "LilyWriter: glissando id % starts on % while id % is already open."
                            .format(endpoint.id, leaf, openId)
                        ).throw
                    };
                    openId = endpoint.id;
                    openAt = at;
                };
            };
            if (openId.notNil) {
                Error(
                    "LilyWriter: glissando from % is still open when the voice ends."
                    .format(leaves[openAt])
                ).throw
            };
        };
        ^this
    }

    // LilyPond pairs noteheads by position. Rastrum stores no `\glissandoMap`.
    prRequireEqualGlissandoWidth { |from, to|
        var width = Validator.prGlissandoWidth(from);
        if (width != Validator.prGlissandoWidth(to)) {
            Error(
                "LilyWriter: glissando runs from % noteheads to %. Both ends need the same count."
                .format(width, Validator.prGlissandoWidth(to))
            ).throw
        };
        ^this
    }

    prRequireOneAttackBetween { |leaves, fromAt, toAt|
        var at = fromAt + 1;
        while { at < toAt } {
            if (Validator.prContinues(leaves[at - 1], leaves[at]).not) {
                Error(
                    "LilyWriter: glissando from % must stop on the next attack; % is struck between them."
                    .format(leaves[fromAt], leaves[at])
                ).throw
            };
            at = at + 1;
        };
        ^this
    }

    // A ramp is drawn inside one staff.
    prRequireNoOpenRampsAnywhere { |situation|
        if (openRamps.notEmpty) {
            Error(
                "LilyWriter: a tempo ramp with id % is still open, but %. A span needs both ends in the same staff."
                .format(openRamps.keys.asArray.sort.join(", "), situation)
            ).throw
        };
        ^this
    }

    // Words before the start leaf, through the same grob as text spanners.
    prRampPrefixAt { |leaf|
        var out = "";
        (pendingRamps[leaf] ? []).do { |endpoint|
            if (endpoint.isRampStart and: { endpoint.hasText }) {
                out = out ++ "\\once \\override TextSpanner.bound-details.left.text "
                    ++ "= \\markup { \"" ++ LilyWriter.markupString(endpoint.text)
                    ++ "\" } "
            }
        };
        ^out
    }

    // Markers after the leaf, where post-events go.
    prRampMarksAt { |leaf|
        var out = "";
        (pendingRamps[leaf] ? []).do { |endpoint|
            out = out ++ if (endpoint.isRampStart) {
                "\\startTextSpan"
            } {
                "\\stopTextSpan"
            }
        };
        ^out
    }

    // A skip of any written length.
    *skipString { |span| ^"s" ++ this.spanString(span) }

    // Emitted before the leaf it stands on.
    prWriteDirectionsAt { |leaf|
        pendingDirections !? {
            pendingDirections[leaf] !? { |found|
                found.do { |direction|
                    stream << LilyWriter.directionString(direction)
                }
            }
        };
        ^this
    }

    visitMeasure { |measure|
        measureRests = IdentitySet.newFrom(measure.wholeBarRests);
        pendingDirections = this.prDirectionsByLeaf(measure);
        pendingRamps = this.prRampsByLeaf(measure);
        // Clef before directions and meter.
        measure.clef !? { |clef|
            if (clef != currentClef) {
                currentClef = clef;
                stream << "\\clef " << clef << " "
            }
        };
        measure.directions.do { |direction|
            if (direction.atBarStart and: { direction.isTempoRamp.not }) {
                stream << LilyWriter.directionString(direction)
            }
        };
        // On change only. LilyPond keeps a meter until replaced.
        if (measure.meter.notNil and: {
            (measure.meter != currentMeter) or: { restoreMeter } }) {
            currentMeter = measure.meter;
            restoreMeter = false;
            stream << LilyWriter.meterString(measure.meter) << " "
        };
        this.prRequirePlaceableMeasure(measure);
        if (measure.isPartial) {
            stream << LilyWriter.partialDirective(measure);
            // `\set Timing.measureLength` holds until a `\time` restores it.
            // `\partial` restores itself.
            if (measure.isAnacrusis.not) { restoreMeter = true };
        };
        if (measure.hasVoices) {
            stream << "<< ";
            measure.children.do { |voice, i|
                if (i > 0) { stream << "\\\\ " };
                stream << "{ ";
                voice.accept(this);
                stream << "} ";
            };
            stream << ">> ";
        } {
            this.writeChildren(measure);
        };
        stream << "|\n    ";
    }

    visitStaff { |staff|
        var lane;
        stream << "\\new Staff ";
        if (staff.name.notNil or: { staff.shortName.notNil }) {
            stream << "\\with {";
            // Escaped: a quote would end the string and leave stray tokens.
            staff.name !? { |text|
                stream << " instrumentName = \""
                       << LilyWriter.markupString(text) << "\""
            };
            staff.shortName !? { |text|
                stream << " shortInstrumentName = \""
                       << LilyWriter.markupString(text) << "\""
            };
            stream << " } "
        };
        staffNumber = staffNumber + 1;

        // The hidden tempo lane runs beside the music.
        //
        // See Note [The MIDI lane is one staff].
        lane = if (staffNumber == 1) { this.prTempoLaneString(staff) } { "" };

        stream << if (lane.isEmpty) { "{\n    " } { "<<\n    {\n    " };

        // This sets the Staff property rather than `\\autoBeamOff`,
        // because voices created later inside `<< ... \\\\ ... >>`
        // don't inherit the command.

        stream << "\\set Staff.autoBeaming = ##f\n    ";

        // Numbers, not C and cut-C. Per staff, so no `\layout` drops it.
        stream << "\\numericTimeSignature\n    ";

        // A staff can't know what the one before left in force.
        currentMeter = nil;

        currentClef = staff.clef;
        openRamps = Dictionary.new;
        if (staff.clef.notNil) { stream << "\\clef " << staff.clef << "\n    " };
        this.writeChildren(staff);
        this.prRequireNoOpenRampsAnywhere(
            "staff % ends".format(staffNumber));
        stream << if (lane.isEmpty) {
            "\n  }\n  "
        } {
            "\n    }\n    { " ++ lane ++ "}\n  >>\n  "
        };
    }

    // Derived tempo steps as a hidden voice of skips.
    //
    // Every step is rounded.
    //
    // LilyPond `\tempo` takes a whole number. Gaps are skips of any
    // written length.
    prTempoLaneString { |staff|
        var out = CollStream.on(String.new);
        var at = Duration(0, 1);
        var total = staff.duration;

        if (laneRecords.isEmpty) { ^"" };
        laneRecords.do { |record|
            var gap = record[\offset] - at;
            if (gap > Duration(0, 1)) {
                out << LilyWriter.skipString(gap) << " ";
                at = record[\offset];
            };
            out << "\\once \\override Score.MetronomeMark.stencil = ##f "
                << "\\tempo 4 = " << record[\bpm].round.max(1).asInteger << " ";
        };
        if (total > at) { out << LilyWriter.skipString(total - at) << " " };
        ^out.collection
    }

    // The `\layout` block belongs to the profile. This writer only places it.
    writeLayout {
        stream << profile.layoutString;
        ^this
    }

    visitScore { |score|
        var header = [];
        stream << "\\version \"" << Rastrum.lilypondVersion << "\"\n";
        // One `\header` block, shared by score metadata and profile lines.
        if (score.title.notNil) {
            header = header.add("title = \"" ++ LilyWriter.markupString(score.title) ++ "\"")
        };
        if (score.composer.notNil) {
            header = header.add("composer = \"" ++ LilyWriter.markupString(score.composer) ++ "\"")
        };
        header = header ++ profile.headerLines;
        if (header.notEmpty) {
            stream << "\\header {\n";
            header.do { |line| stream << "  " << line << "\n" };
            stream << "}\n";
        };
        // Profile text that belongs above `\score`, such as paper or staff size.
        stream << profile.preambleString;
        stream << "\\score {\n  <<\n  ";
        this.writeChildren(score);
        stream << "\n  >>\n";
        this.writeLayout;
        if (includeMidi) { stream << "  \\midi { }\n" };
        stream << "}\n";
    }
}
