// LilyWriter (LilyPond output). All LilyPond vocabulary lives in this file.
//
// The one writer that implies an external binary: `Rastrum.render` engraves
// what this produces, and `Rastrum.lilypondVersion` decides which of two
// grouped-meter spellings it emits (see
// Note [Two spellings for one grouped meter] below). Which of them a given
// binary will read is
// Note [The writer declares a version, render meets a binary] in Rastrum.sc.
//
// It also switches LilyPond's own beaming off, once per staff (see
// Note [LilyPond infers beams, Rastrum does not]).
LilyWriter : ScoreWriter {
    classvar <alterationNames, <articulationMarks, <layouts;

    // The rests of the bar being written that are a whole bar of silence, asked
    // of the measure once and consulted as each of them is reached.
    var measureRests, includeMidi, pendingDirections;

    // The clef in force, so a bar that asks for the one already sounding writes
    // nothing. Tracked exactly as `MusicXMLWriter` tracks the meter, and for
    // the same reason: a redundant clef is not wrong, but it is noise in the
    // .ly and an extra thing for a reader to wonder about.
    var currentClef;

    // Which `\layout` block the document carries. See Note [A layout is the
    // writer's, not the model's].
    var layout;

    *initClass {
        // index = alterationSteps + 4, i.e. -2 .. +2 semitones by quarter tones
        alterationNames = ["eses", "eseh", "es", "eh", "", "ih", "is", "isih", "isis"];
        articulationMarks = IdentityDictionary[
            \staccato -> "-.", \staccatissimo -> "-!", \tenuto -> "--",
            \accent -> "->", \marcato -> "-^"
        ];
        layouts = [\default, \complexRhythm];
    }


    // Note [A layout is the writer's, not the model's]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Spacing is a LilyPond concept, so the name lives here and not on the
    // score. A `MusicScore` carrying `layout: \complexRhythm` would be a model
    // that knows what a `proportionalNotationDuration` is, and by invariant 1
    // the next backend would then have to answer for a word meaning nothing to
    // it. `Rastrum.render` passes the name through rather than storing it.
    //
    // Two so far. `\default` is an empty `\layout { }`, which is what LilyPond
    // decides for itself and what every document written before this said.
    // `\complexRhythm` is for music whose rhythm is the difficult part: notes
    // spaced by how long they last rather than by how much ink they need, and
    // tuplets that print the whole ratio.
    //
    // A closed set, checked where a writer is built, by
    // Note [Refuse at the constructor] in MusicPitch.sc. A misspelled layout is
    // then an error at the call site rather than a `\layout { }` that silently
    // did nothing.
    // `midi` writes `\\midi { }` as well, so an engraved score is also a
    // playable one. It costs nothing in the .ly, and a metronome mark arrives at
    // the speed it prints, `\\tempo 4 = 132` being what sets a MIDI tempo.
    //
    // `layout` is the block above.
    //
    // >>> LilyWriter.new(layout: \complexRhythm).layout   -> complexRhythm
    // >>> try { LilyWriter.new(layout: \spacious) } { \refused }
    // refused


    *new { |midi = true, layout = \default| ^super.new.init(midi, layout) }

    init { |midi = true, argLayout = \default|
        includeMidi = midi;
        layout = this.class.checkedLayout(argLayout);
        ^this
    }

    layout { ^layout }

    *checkedLayout { |name|
        if (layouts.includes(name.asSymbol).not) {
            Error("LilyWriter: % is not a layout. The layouts are %."
                .format(name.asCompileString, layouts.join(", "))).throw
        };
        ^name.asSymbol
    }

    prepare { |element|
        measureRests = IdentitySet.new;
        pendingDirections = IdentityDictionary.new;
        currentClef = nil;
        ^this
    }

    // Text inside `\\markup { "..." }` is a LilyPond string, where a backslash
    // opens an escape and a quote ends the string. Both have to be spelled as
    // themselves, backslash first or it would escape the escapes just added.
    *markupString { |text|
        ^text.asString.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    // Post-events, in the order LilyPond conventionally takes them:
    // articulations against the note head, then the dynamic, then any text,
    // then the tie. An unmarked note emits nothing here, so ordinary output is
    // untouched.
    //
    // `^` puts a post-event above the staff and `_` below: the direction
    // indicators LilyPond gives every post-event, and the whole of what
    // placement costs to spell here.
    *markingString { |leaf|
        var out = "";
        leaf.articulations.do { |marking|
            out = out ++ (articulationMarks[marking.value]
                ?? { Error("LilyWriter: no mark for articulation %".format(
                    marking.value)).throw })
        };
        leaf.dynamics.do { |marking| out = out ++ "\\" ++ marking.value };
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

    // What a spanner needs said *before* the note it starts on.
    // `\\startTextSpan` is a post-event and goes with the others, but the words
    // it draws are a property of the TextSpanner grob and have to be set first,
    // with `\\once`, so the override lasts exactly as long as the one spanner
    // it belongs to.
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

    // The grace group before the leaf it ornaments. Its leaves are ordinary
    // notes and chords whose duration is a note head rather than a length, so
    // they spell the way any note spells and the group takes no time in the
    // bar around it.
    //
    // The command is chosen here rather than read off `graceStyle`, which
    // happens to share LilyPond's two words. Spelling is the writer's, and a
    // model symbol that matched by luck would be an output format in the model
    // the first time only one of them changed.
    //
    // Before `spannerPrefix`, not after: that prefix is `\once`, which binds to
    // the next music event, and a grace group standing between them would take
    // the override meant for the host.
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
    // A grouped meter prints additively, 2+3 over 8, and LilyPond changed how
    // that is written. Up to 2.25.33 it is `\\compoundMeter #'((2 3 8))`. In
    // 2.25.34 the complex `\\time` form `#'((2 3) . 8)` became the spelling
    // this writer uses. Neither spelling works on both sides of that line: the
    // old one is an unknown command on the new series, and the new one is not a
    // time signature the old series can parse.
    //
    // So the writer emits whichever matches the `\\version` it is already
    // putting at the top of the file, and `Rastrum.lilypondVersion` decides
    // both. A document that declares one version and speaks another is the one
    // outcome worth ruling out.
    //
    // The two spellings engrave identically, every glyph outline matching,
    // which is how the older one stays verified on a machine that no longer
    // has the function under its old name.
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

    // Whether the declared LilyPond understands `\\time #'((2 3) . 8)`, which
    // arrived in 2.25.34 along with the rename that removed `\\compoundMeter`.
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
    // A slur's default is unnumbered. Anything overlapping it needs `\\=n` so
    // the closing mark can say which one it closes, and id 1 is the unnumbered
    // one. A hairpin takes no number at all: LilyPond spells one per voice at a
    // time and has no way to say which of two a `\\!` closes, which is why the
    // validator refuses overlapping hairpins rather than this inventing an id
    // for them.
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
        // A beam is unnumbered: LilyPond has one beam open at a time and closes
        // it with the next `]`, which is why \beam is nonOverlapping in the
        // model. Stops are written before starts by the caller, so two adjacent
        // groups come out as `][` on the note between them.
        if (endpoint.isBeam) {
            ^if (endpoint.isStart) { "[" } { "]" }
        };
        if (endpoint.isSlur.not) {
            Error("LilyWriter: no spelling for a % spanner".format(endpoint.kind)).throw
        };
        mark = if (endpoint.isStart) { "(" } { ")" };
        ^if (endpoint.id == 1) { mark } { "\\=" ++ endpoint.id ++ mark }
    }

    // The undotted value as LilyPond spells it, plus one `.` per dot. Compare
    // MusicXMLWriter.typeString, which spells the same `Duration#notation`
    // pair its own way. That pair is all the model says.
    //
    // >>> LilyWriter.durationString(Duration(3, 8)).asCompileString  -> "4."
    // >>> LilyWriter.durationString(Duration(7, 16)).asCompileString -> "4.."
    // >>> LilyWriter.durationString(Duration(2, 1)).asCompileString
    // "\\breve"
    *durationString { |dur|
        var pair = dur.notation, value, dots;
        if (pair.isNil) {
            Error("LilyWriter: % is not notatable as one leaf. Run "
                "ScorePrepare.run on the tree first, or go through Rastrum.render, "
                "which prepares by default.".format(dur)).throw
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
    // >>> LilyWriter.pitchString(MusicPitch(\c, \quarterSharp, 5))
    //     .asCompileString   -> "cih''"
    *pitchString { |pitch|
        var index = pitch.alterationSteps + 4;
        var marks = pitch.octave - 3;  // LilyPond: c' is middle C
        if (index < 0 or: { index >= alterationNames.size }) {
            Error("LilyWriter: alteration % out of range".format(pitch.alter)).throw
        };
        ^pitch.letter.asString
            ++ alterationNames[index]
            ++ if (marks >= 0) { String.fill(marks, { $' }) } { String.fill(marks.neg, { $, }) }
    }

    // LilyPond marks a tie once, on the note it leaves from. The continuation
    // note says nothing. Whether a partner actually follows is not a question
    // this writer can answer locally. See the note in MusicXMLWriter.
    visitNote { |note|
        this.prWriteDirectionsAt(note);
        stream << LilyWriter.graceString(note);
        stream << LilyWriter.spannerPrefix(note);
        stream << LilyWriter.pitchString(note.pitch)
               << LilyWriter.durationString(note.dur)
               << LilyWriter.markingString(note);
        if (note.tiesToNext) { stream << "~" };
        stream << " ";
    }

    // `R` is LilyPond's whole-bar rest: centered, free of the note values, and
    // taking the measure's own span rather than a duration to be read. The bar
    // decides which rests are those. This writer only spells them.
    visitRest { |rest|
        this.prWriteDirectionsAt(rest);
        stream << LilyWriter.graceString(rest);
        stream << LilyWriter.spannerPrefix(rest);
        if (measureRests.notNil and: { measureRests.includes(rest) }) {
            stream << "R" << LilyWriter.spanString(rest.dur) << " ";
            ^this
        };
        stream << "r" << LilyWriter.durationString(rest.dur)
               << LilyWriter.markingString(rest) << " ";
    }

    // A whole chord tie goes after the chord, `<c' e' g'>4~`. A partial one
    // goes on the individual noteheads inside it, `<c'~ e' g'~>4`. LilyPond
    // spells the two cases differently, so the writer picks.
    visitChord { |chord|
        var whole = chord.tiesAll;
        this.prWriteDirectionsAt(chord);
        stream << LilyWriter.graceString(chord);
        stream << LilyWriter.spannerPrefix(chord);
        stream << "<"
            << chord.pitches.collect { |p, i|
                LilyWriter.pitchString(p)
                    ++ if (whole.not and: { chord.tiesToNext[i] }) { "~" } { "" }
            }.join(" ")
            << ">" << LilyWriter.durationString(chord.dur)
            << LilyWriter.markingString(chord);
        if (whole) { stream << "~" };
        stream << " ";
    }

    visitTuplet { |tuplet|
        if (tuplet.isTrivial) { ^this.writeChildren(tuplet) };
        stream << "\\tuplet " << tuplet.actualNotes << "/" << tuplet.normalNotes << " { ";
        this.writeChildren(tuplet);
        stream << "} ";
    }

    // A voice is just its contents. The simultaneity below is what separates
    // them.
    visitVoice { |voice| this.writeChildren(voice) }

    // A span written as a duration, for the two places LilyPond takes one:
    // after `\\partial` and after `R`. It only spells a single note value
    // plainly, so a span that is not one note head is written in scaled form,
    // `1*5/8`, which says the same thing without needing a note that does not
    // exist.
    *spanString { |span|
        ^if (span.isNotatable) {
            LilyWriter.durationString(span)
        } {
            "1*" ++ span.numerator ++ "/" ++ span.denominator
        }
    }

    // LilyPond has two ways to shorten a bar and they are not interchangeable,
    // so the model's two facts do not collapse into one directive here.
    //
    // `\\partial d` works by setting the position to -d, so that d of music
    // arrives exactly at the barline: it always means a span sitting at the
    // *end* of its meter, which is what an anacrusis is. A bar short at the
    // other end is a different fact. That one shortens the measure itself, and
    // the `\\time` the next bar emits restores the length.
    //
    // Between those two there is nothing, which is what
    // `prRequirePlaceableMeasure` has already refused by the time this is
    // reached.
    *partialDirective { |measure|
        if (measure.isAnacrusis) {
            ^"\\partial " ++ LilyWriter.spanString(measure.barDuration) ++ " "
        };
        ^"\\set Timing.measureLength = #(ly:make-moment %/%) ".format(
            measure.barDuration.numerator, measure.barDuration.denominator)
    }

    // One voice reads as it always did. Two or more go inside << ... \\ ... >>,
    // which is LilyPond's simultaneous-music construct. The voices start
    // together at the barline rather than following one another. A direction
    // stands before the music of its bar.
    //
    // `\\tempo` is its own construct. A rehearsal mark and system text are both
    // `\\mark`. See Note [What each backend cannot say] in ScoreWriter.sc.
    //
    // `\\tempo` takes prose, a metronome mark, or both in that order, which is
    // the same three shapes a `Direction` admits, so this is a spelling and
    // not a translation. The metronome half is the one that reaches LilyPond's
    // own MIDI: `\\tempo "Allegro"` prints a word and leaves the file at its
    // default speed, `\\tempo 4 = 132` sets it.
    *directionString { |direction|
        var out;
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

    // Returns the leaf each mid-bar direction stands before, as `leaf ->
    // [direction]`.
    //
    // Refused again here, by Note [Where a direction may sit] and
    // Note [A writer refuses what it depends on], both in ScoreWriter.sc: a bar
    // whose upper voice happens to have a leaf at the offset and whose lower
    // voice is holding one would otherwise engrave a mark the lower voice never
    // reaches.
    //
    // Leaf positions come from `ScorePrepare.leafOffsetsIn` rather than from a
    // walk of this writer's own, because where a leaf sits in its bar is a fact
    // about the tree and not about LilyPond, for the same reason `wholeBarRests`
    // lives on `Measure`. Two copies of that arithmetic is how the writers come
    // to disagree about one score.
    //
    // The mark is then written once, into the first voice: which voice carries
    // it is a spelling decision, and a free one only because every voice had to
    // agree.
    prDirectionsByLeaf { |measure|
        var pending = IdentityDictionary.new;
        var offsets, local;
        if (measure.directions.every { |direction| direction.atBarStart }) {
            ^pending
        };
        offsets = ScorePrepare.leafOffsetsIn(measure);
        local = { |leaf| offsets[leaf] - measure.metricOffset };
        measure.directions.reject { |direction| direction.atBarStart }
            .do { |direction|
                var missing = measure.voices.detectIndex { |voice|
                    voice.leaves.any { |leaf| local.(leaf) == direction.offset }.not
                };
                var leaf;
                if (missing.notNil) {
                    Error("LilyWriter: \"%\" is written % into this bar, and voice "
                        "% has nothing beginning there. A mark sits between "
                        "leaves, in every voice at once. Validator.validate names "
                        "the offsets a bar allows; Rastrum.render runs it.".format(
                            direction.text, direction.offset, missing + 1)).throw
                };
                leaf = measure.voices.first.leaves.detect { |each|
                    local.(each) == direction.offset
                };
                pending[leaf] = (pending[leaf] ? []) ++ [direction];
            };
        ^pending
    }

    // Emitted before the leaf it stands on, so `\tempo "Rit." c'4` reads in the
    // order it is played.
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
        // Before the directions, because a clef change belongs to the barline
        // rather than to anything said over the bar, and before the meter,
        // which is the order the two are engraved in.
        measure.clef !? { |clef|
            if (clef != currentClef) {
                currentClef = clef;
                stream << "\\clef " << clef << " "
            }
        };
        measure.directions.do { |direction|
            if (direction.atBarStart) {
                stream << LilyWriter.directionString(direction)
            }
        };
        if (measure.meter.notNil) {
            stream << LilyWriter.meterString(measure.meter) << " "
        };
        this.prRequirePlaceableMeasure(measure);
        if (measure.isPartial) {
            stream << LilyWriter.partialDirective(measure)
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
        stream << "\\new Staff ";
        if (staff.name.notNil) {
            stream << "\\with { instrumentName = \"" << staff.name << "\" } "
        };
        stream << "{\n    ";
        // Note [LilyPond infers beams, Rastrum does not]
        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        //
        // Beams are authored in this model, so a beam in the output that nobody
        // wrote is a notation only one backend has. The leak the whole design
        // exists to prevent. LilyPond beams eighths by itself unless told not
        // to, and would beam them again from a compound meter's beat structure,
        // so it is told not to once per staff.
        //
        // `\\set Staff.autoBeaming = ##f` and not `\\autoBeamOff`: the latter
        // sets the property in the current Voice, and the voices of `<< ...
        // \\\\ ... >>` are created after it and do not inherit it. Engraving
        // both spellings shows it: the voiced staff still comes out beamed
        // under `\\autoBeamOff` and unbeamed under this one.
        stream << "\\set Staff.autoBeaming = ##f\n    ";
        currentClef = staff.clef;
        if (staff.clef.notNil) { stream << "\\clef " << staff.clef << "\n    " };
        this.writeChildren(staff);
        stream << "\n  }\n  ";
    }

    // The `\layout` block, and the only place a spacing decision is spelled.
    //
    // `\default` writes what every document said before there was a choice, so
    // a score not asking for one is byte identical to what it was.
    //
    // `\complexRhythm` uses four settings for dense or irregular rhythm:
    //
    //   proportionalNotationDuration      space by sounding duration
    //   tupletFullLength                  span the full tuplet duration
    //   TupletNumber.text                 print 7:6 rather than a bare 7
    //   TupletBracket.bracket-visibility  show brackets under beams
    //
    // The TupletBracket overrides keep the right hook visible at barlines, leave
    // room above the staff, set a minimum bracket size, and let brackets push
    // notes apart.
    //
    // 1/16 keeps ordinary sixteenths readable. Denser material can choose a
    // smaller value explicitly.
    writeLayout {
        if (layout == \default) { stream << "  \\layout { }\n"; ^this };
        stream << "  \\layout {\n"
               << "    indent = #0\n"
               << "    \\context {\n"
               << "      \\Score\n"
               << "      proportionalNotationDuration = #1/16\n"
               << "      tupletFullLength = ##t\n"
               << "      tupletFullLengthNote = ##f\n"
               << "      \\override TupletNumber.text = "
               << "#tuplet-number::calc-fraction-text\n"
               << "      \\override TupletBracket.bracket-visibility = ##t\n"
               << "      \\override TupletBracket.full-length-to-extent = ##f\n"
               << "      \\override TupletBracket.shorten-pair = #'(-0.2 . 0.35)\n"
               << "      \\override TupletBracket.padding = #2\n"
               << "      \\override TupletBracket.minimum-length = #3\n"
               << "      \\override TupletBracket.springs-and-rods = "
               << "#ly:spanner::set-spacing-rods\n"
               << "    }\n"
               << "  }\n";
        ^this
    }

    visitScore { |score|
        stream << "\\version \"" << Rastrum.lilypondVersion << "\"\n";
        if (score.title.notNil or: { score.composer.notNil }) {
            stream << "\\header {\n";
            if (score.title.notNil)    { stream << "  title = \"" << score.title << "\"\n" };
            if (score.composer.notNil) { stream << "  composer = \"" << score.composer << "\"\n" };
            stream << "}\n";
        };
        stream << "\\score {\n  <<\n  ";
        this.writeChildren(score);
        stream << "\n  >>\n";
        this.writeLayout;
        if (includeMidi) { stream << "  \\midi { }\n" };
        stream << "}\n";
    }
}
