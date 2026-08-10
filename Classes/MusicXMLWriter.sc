// MusicXMLWriter: MusicXML 4.0 partwise output.
//
// All MusicXML vocabulary lives in this file, and no external binary is needed.
//
// The writer carrying the most state, because MusicXML restates where LilyPond
// infers: the clef and meter in force, the ties still open per voice, the beam
// level of the group being written, and a divisions count for the whole score.
// That last one is the awkward part and is explained below.
//
// Note [Only a score is a document]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
// The DOCTYPE, `<score-partwise>` and `<part-list>` are written by `visitScore`
// and nothing else, so writing anything below a `MusicScore` gives loose
// `<measure>` elements: useful to assert on, and not a file any reader opens.
// That is deliberate and the tests depend on it, `ScoreContainer` being the
// smallest thing a refusal can be provoked with.
//
// It differs from the other two writers, and both differences are intended. A
// LilyPond fragment is ordinary LilyPond, so `LilyWriter` needs no such rule.
// A JSON fragment carries no format or version, so `ScoreJSONWriter` refuses
// one outright. MusicXML sits between them: the fragment is well formed and
// worth having, but it is not a document, which is why `Rastrum.writeMusicXML`
// refuses to put one in a file where the extension would promise otherwise.

MusicXMLWriter : ScoreWriter {
    // MusicXML counts time in divisions per quarter note, so every duration in
    // a score has to be a whole number of them. A fixed value cannot do that:
    // 768 has no factor of five, so each fifth of a 4/4 bar rounds to 614 and
    // the five sum to 3070 against a bar of 3072. The model is exact and the
    // wire was two ticks short.
    //
    // `baseDivisions` is only a floor, kept so ordinary binary music still says
    // 768 and looks like what every other exporter emits. The value actually
    // written is the least common multiple of that and whatever the score
    // needs.
    //
    // `maxDivisions` is where exactness stops being worth having. A stack of
    // coprime tuplets can demand a value large enough that importers mishandle
    // it, and a file that renders as something the composer did not write is
    // worse than an error at the call site. It is settable because that ceiling
    // is a judgement about a particular importer rather than a fact about
    // MusicXML, which puts no limit on the value.
    classvar <baseDivisions = 768;
    classvar <>maxDivisions = 65536;
    classvar <typeNames, <articulationTags;

    var measureNumber, currentMeter, timeModification, tupletStarts, tupletStops;
    var tupletDepth;
    var pendingTies, divisions, currentVoice, atPartStart, measureRests, beamOpen;

    // The clef in force in the part being written. MusicXML states a clef
    // inside `<attributes>` and it stays in force until restated, exactly as
    // the meter does. This is tracked like `currentMeter`: restating an
    // unchanged one is legal, but noisy.
    var currentClef, staffClef;

    *initClass {
        typeNames = IdentityDictionary[
            2 -> "half", 4 -> "quarter", 8 -> "eighth", 16 -> "16th",
            32 -> "32nd", 64 -> "64th", 128 -> "128th", 256 -> "256th",
            512 -> "512th", 1024 -> "1024th"
        ];
        // marcato is <strong-accent/> here. The model's name is neutral of
        // both.
        articulationTags = IdentityDictionary[
            \staccato -> "staccato", \staccatissimo -> "staccatissimo",
            \tenuto -> "tenuto", \accent -> "accent", \marcato -> "strong-accent"
        ];
    }

    prepare { |element|
        measureNumber = 0;
        atPartStart = true;
        measureRests = IdentitySet.new;
        currentMeter = nil;
        timeModification = nil;
        tupletStarts = IdentityDictionary.new;
        tupletStops = IdentityDictionary.new;
        tupletDepth = 0;
        pendingTies = Dictionary.new;
        currentVoice = nil;
        beamOpen = false;
        currentClef = nil;
        staffClef = nil;
        divisions = MusicXMLWriter.divisionsFor(element);
        ^this
    }

    // Returns the divisions per quarter this score needs to be counted exactly.
    //
    // A leaf of prolated duration d lasts d * 4 quarter notes, so d * 4 *
    // divisions is its tick count. That is a whole number exactly when
    // `divisions` is a multiple of the denominator of d * 4, so the answer is
    // the least common multiple of those denominators and the base. This keeps
    // simple music looking conventional.
    *divisionsFor { |element|
        var needed = baseDivisions;
        element.leaves.do { |leaf|
            needed = needed.lcm((leaf.prolatedDuration * Duration(4, 1)).denominator)
        };
        // A direction's offset is counted in the same divisions the notes are,
        // so it has to divide out too, through the same conversion rather than
        // its raw denominator. `divisionsFor` counts quarter notes, so an
        // offset of 1/8 needs a denominator of 2 rather than 8. Taking the raw
        // one would demand four times the divisions the music actually needs,
        // and `maxDivisions` is a real ceiling for a score to be pushed into
        // for no reason.
        element.traverse { |node|
            if (node.isKindOf(Measure)) {
                node.directions.do { |direction|
                    needed = needed.lcm(
                        (direction.offset * Duration(4, 1)).denominator)
                }
            }
        };
        if (needed > maxDivisions) {
            Error("MusicXMLWriter: counting this score exactly needs % divisions "
                "per quarter, past the % this writer will emit. Simplify the "
                "tuplets, or raise MusicXMLWriter.maxDivisions knowing that "
                "importers handle large values unevenly.".format(
                    needed, maxDivisions)).throw
        };
        ^needed
    }

    // A tie left open at the end of the music is as broken as one left open
    // inside a bar. Nothing later would catch it, so catch it here.
    write { |element|
        var result = super.write(element);
        this.prRequireNoPendingTiesAnywhere("the music ends");
        ^result
    }

    // Exact all the way to the integer: no asFloat, no rounding. `divisionsFor`
    // has already guaranteed this divides out, so a leftover denominator means
    // the tree changed under the writer rather than that a duration was
    // awkward.
    ticks { |dur|
        var exact = dur * Duration(4 * divisions, 1);
        if (exact.denominator != 1) {
            Error("MusicXMLWriter: % is not a whole number of ticks at % "
                "divisions per quarter".format(dur, divisions)).throw
        };
        ^exact.numerator
    }

    // The undotted value as MusicXML names it. The dots are separate `<dot/>`
    // tags. The counterpart of LilyWriter.durationString, off the same pair:
    // a dotted quarter is a quarter here and "4." there.
    //
    // >>> MusicXMLWriter.typeString(Duration(1, 4)).asCompileString
    // "quarter"
    // >>> MusicXMLWriter.typeString(Duration(3, 8)).asCompileString
    // "quarter"
    // >>> MusicXMLWriter.typeString(Duration(1, 1)).asCompileString
    // "whole"
    *typeString { |dur|
        var pair = dur.notation, value;
        if (pair.isNil) {
            Error("MusicXMLWriter: % is not notatable as one leaf. Run "
                "ScorePrepare.run on the tree first, or go through "
                "Rastrum.writeMusicXML, which prepares by default.".format(dur)).throw
        };
        value = pair[0];
        if (value.denominator == 1) {
            ^switch(value.numerator, 1, { "whole" }, 2, { "breve" }, 4, { "long" },
                { Error("MusicXMLWriter: no type for %".format(value)).throw })
        };
        ^typeNames[value.denominator]
            ?? { Error("MusicXMLWriter: no type for %".format(value)).throw }
    }

    // >>> MusicXMLWriter.escape("a < b & c").asCompileString
    // "a &lt; b &amp; c"
    *escape { |string|
        ^string.asString
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    visitScore { |score|
        stream << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
               << "<!DOCTYPE score-partwise PUBLIC "
               << "\"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" "
               << "\"http://www.musicxml.org/dtds/partwise.dtd\">\n"
               << "<score-partwise version=\"4.0\">\n";

        if (score.title.notNil or: { score.composer.notNil }) {
            stream << "  <work><work-title>"
                   << MusicXMLWriter.escape(score.title ? "") << "</work-title></work>\n";
            if (score.composer.notNil) {
                stream << "  <identification><creator type=\"composer\">"
                       << MusicXMLWriter.escape(score.composer)
                       << "</creator></identification>\n"
            };
        };

        stream << "  <part-list>\n";
        score.children.do { |staff, i|
            stream << "    <score-part id=\"P" << (i + 1) << "\">\n"
                   << "      <part-name>" << MusicXMLWriter.escape(staff.name ? "Part")
                   << "</part-name>\n    </score-part>\n";
        };
        stream << "  </part-list>\n";

        // Each part starts clean and must end clean. A tie carries across a
        // barline within one part, but never out of it: parts are written one
        // after another in the file while sounding at the same time, so without
        // this the next part's first note would close a tie the previous part
        // left open, and the two are not even the same instrument.
        score.children.do { |staff, i|
            stream << "  <part id=\"P" << (i + 1) << "\">\n";
            measureNumber = 0;
            atPartStart = true;
            currentMeter = nil;
            // The staff's own clef is what the part opens in. A bar may change
            // it afterwards. Held here rather than read in `visitMeasure`,
            // which is never shown the staff it belongs to.
            staffClef = staff.clef;
            currentClef = nil;
            pendingTies = Dictionary.new;
            staff.accept(this);
            this.prRequireNoPendingTiesAnywhere(
                "part % ends".format(i + 1));
            stream << "  </part>\n";
        };
        stream << "</score-partwise>\n";
    }

    // `staffClef` is set here as well as in the part loop, so a bare Staff,
    // written without a score around it as `LilyWriter` allows, says what
    // clef it is in rather than dropping it. That path was the one place this
    // writer still disagreed with LilyPond about a clef.
    visitStaff { |staff| staffClef = staff.clef; this.writeChildren(staff) }

    visitMeasure { |measure|
        // A pickup is measure 0 and `implicit`: it is present in the music but
        // carries no printed number, and the first full bar is still bar 1.
        //
        // Being short is not enough to be one, and being first is not either. A
        // pickup is a bar that both begins the part and ends at the barline. A
        // bar that begins the part and begins at the barline is a truncated
        // measure 1, and numbering it 0 would renumber the whole part while
        // hiding the number that says so. `implicit` means the number never
        // appears, which is true of an anacrusis and of nothing else here.
        var pickup = atPartStart and: { measure.isAnacrusis };
        // Captured before `atPartStart` is cleared below: the staff's own clef
        // is written on the bar that opens the part, and only there.
        var opening = atPartStart;

        measureRests = IdentitySet.newFrom(measure.wholeBarRests);
        this.prRequirePlaceableMeasure(measure);
        if (pickup.not) { measureNumber = measureNumber + 1 };
        atPartStart = false;
        stream << "    <measure number=\"" << if (pickup) { 0 } { measureNumber } << "\"";
        if (pickup) { stream << " implicit=\"yes\"" };
        stream << ">\n";
        this.writeAttributes(measure, opening);
        // MusicXML writes voices one after another and then rewinds the clock
        // with <backup>, so the next voice starts at the barline again. Every
        // note carries a <voice> number saying which timeline it belongs to.
        //
        // Directions come before the notes of the bar, which is where they are
        // heard and where an importer expects them. MusicXML has <rehearsal>
        // and <words> as different things, so the distinction LilyPond loses is
        // kept.
        //
        // A mid-bar direction stays here and carries an <offset> instead of
        // being moved into the note stream: MusicXML measures the offset in
        // divisions from where the <direction> stands, so the bar's start is
        // the one position that needs no arithmetic to read back. This is the
        // half LilyPond cannot do. There, a mark is where it is written.
        measure.directions.do { |direction|
            this.writeDirection(MusicXMLWriter.directionBodies(direction),
                \above, direction.offset, direction.quarterPerMinute)
        };
        if (measure.hasVoices) {
            measure.children.do { |voice, i|
                if (i > 0) {
                    stream << "      <backup>\n        <duration>"
                           << this.ticks(measure.barDuration)
                           << "</duration>\n      </backup>\n";
                };
                currentVoice = i + 1;
                // Rastrum keeps a beam inside one bar and one voice, so each
                // timeline starts with none open. Cleared rather than assumed:
                // a stale true would beam the first note of the next voice to
                // nothing.
                beamOpen = false;
                voice.accept(this);
            };
            currentVoice = nil;
            beamOpen = false;
        } {
            this.writeChildren(measure);
        };
        stream << "    </measure>\n";
    }

    // Nothing is reset here: `pendingTies` is kept per voice, so the tie a
    // voice leaves open at the end of one bar is closed by that same voice in
    // the next. A tie still open when the music ends is `write`'s to catch.
    visitVoice { |voice| this.writeChildren(voice) }

    visitTuplet { |tuplet|
        var saved = timeModification;
        var savedDepth = tupletDepth;
        var number = tupletDepth + 1;
        var leaves = tuplet.leaves;
        if (tuplet.isTrivial) { ^this.writeChildren(tuplet) };
        // Accumulated as counts, not as a multiplier: multiplying the
        // multipliers would reduce, and reducing loses what was authored. The
        // counts multiply cleanly and still give a nested note the whole
        // modification that applies to it. See Note [A bracket is two facts] in
        // MusicScore.sc.
        timeModification = if (saved.isNil) {
            [tuplet.actualNotes, tuplet.normalNotes]
        } {
            [saved[0] * tuplet.actualNotes, saved[1] * tuplet.normalNotes]
        };
        // Numbered by nesting depth. Simultaneous starts open outer to inner.
        // simultaneous stops close inner to outer.
        tupletDepth = number;
        if (leaves.notEmpty) {
            tupletStarts[leaves.first] = (tupletStarts[leaves.first] ? []) ++ [number];
            tupletStops[leaves.last] = [number] ++ (tupletStops[leaves.last] ? []);
        };
        this.writeChildren(tuplet);
        tupletDepth = savedDepth;
        timeModification = saved;
    }

    // MusicXML states a tie twice, once on the note it leaves and once on the
    // note it arrives at, so unlike LilyPond this writer has to know what
    // follows. That is why the forward flags on the model are enough for both:
    // the arriving half is derivable, and only the writer that needs it pays.
    //
    // `pendingTies` is the set of pitches that must be continued by the next
    // pitched leaf. A chord can leave several, and a partial chord tie leaves a
    // subset, so this is a collection rather than a single pitch.
    //
    // A pending tie that cannot be resolved is thrown rather than dropped. The
    // alternative is a file that opens a tie and never closes it, which
    // importers accept and then render as something the composer did not write.
    visitNote { |note|
        var stopsHere = this.prConsumePendingTies([note.pitch], "a single note");
        this.writeGraces(note);
        this.writeDynamics(note);
        stream << "      <note>\n";
        this.writePitch(note.pitch);
        this.writeBody(note, note.tiesToNext, stopsHere.first);
        stream << "      </note>\n";
        pendingTies[this.prVoiceKey] = if (note.tiesToNext) { [note.pitch] } { [] };
    }

    visitRest { |rest|
        this.prRequireNoPendingTiesInVoice("a rest follows");
        if (measureRests.notNil and: { measureRests.includes(rest) }) {
            ^this.writeMeasureRest(rest)
        };
        this.writeGraces(rest);
        this.writeDynamics(rest);
        stream << "      <note>\n        <rest/>\n";
        this.writeBody(rest);
        stream << "      </note>\n";
    }

    // `measure="yes"` is MusicXML's whole-bar rest: the rest of the measure,
    // however long the measure is. It carries no <type> and no dots, because it
    // has no note value to give, which is also what lets a bar no note head
    // could spell have one at all.
    //
    // The duration is still exact. The shape is about how the bar is drawn. The
    // part would drift if it were about how long the bar is.
    writeMeasureRest { |rest|
        stream << "      <note>\n        <rest measure=\"yes\"/>\n"
               << "        <duration>" << this.ticks(rest.prolatedDuration)
               << "</duration>\n";
        if (currentVoice.notNil) {
            stream << "        <voice>" << currentVoice << "</voice>\n"
        };
        stream << "      </note>\n";
    }

    visitChord { |chord|
        var stops = this.prConsumePendingTies(chord.pitches, "a chord");
        this.writeGraces(chord);
        this.writeDynamics(chord);
        chord.pitches.do { |p, i|
            stream << "      <note>\n";
            if (i > 0) { stream << "        <chord/>\n" };
            this.writePitch(p);
            // Articulations, slurs and beams belong to the chord and not to
            // each notehead, so only the first <note> carries them. The rest
            // are the same chord said again for its other pitches.
            this.writeBody(chord, chord.tiesToNext[i], stops[i], i == 0);
            stream << "      </note>\n";
        };
        pendingTies[this.prVoiceKey] = chord.tiedPitches;
    }

    // Returns one Boolean per arriving pitch, true where a pending tie lands on
    // it. Every pending pitch must be found: a tie into a leaf that does not
    // contain the pitch is an error, not a tie that quietly stops.
    prConsumePendingTies { |arriving, description|
        var key = this.prVoiceKey, pending = pendingTies[this.prVoiceKey] ?? { [] };
        var stops = arriving.collect { |p| pending.any { |waiting| waiting == p } };
        pending.do { |pending|
            if (arriving.any { |p| p == pending }.not) {
                Error("MusicXMLWriter: % ties to the next leaf, but % does not "
                    "contain it. A tie must continue the same pitch.".format(
                        pending, description)).throw
            }
        };
        pendingTies[key] = [];
        ^stops
    }

    // Which timeline's pending ties we are in. A bar without voices is one
    // timeline, keyed 0, so both shapes read the same way.
    prVoiceKey { ^currentVoice ? 0 }

    prRequireNoPendingTiesInVoice { |situation|
        var pending = pendingTies[this.prVoiceKey] ?? { [] };
        if (pending.notEmpty) {
            Error("MusicXMLWriter: % ties to the next leaf, but %.".format(
                pending, situation)).throw
        };
        ^this
    }

    // At the very end nothing may still be waiting, in any voice.
    prRequireNoPendingTiesAnywhere { |situation|
        pendingTies.keysValuesDo { |key, pending|
            if (pending.notEmpty) {
                Error("MusicXMLWriter: % ties onward in voice %, but %. A tie needs "
                    "a note to reach.".format(pending, key, situation)).throw
            }
        };
        ^this
    }

    writePitch { |pitch|
        stream << "        <pitch>\n"
               << "          <step>" << pitch.letter.asString.toUpper << "</step>\n";
        if (pitch.alter.isZero.not) {
            stream << "          <alter>" << pitch.alter.asFloat << "</alter>\n"
        };
        stream << "          <octave>" << pitch.octave << "</octave>\n"
               << "        </pitch>\n";
    }

    // A grace group is `<note>` elements of its own, standing before the note
    // they ornament and carrying no `<duration>`. That absence is the schema's
    // rather than a shortcut here: the content model is
    // `(grace, %full-note;, (tie, tie?)?)`, with no duration anywhere in it, so
    // MusicXML agrees with the model that a grace group takes no measure time.
    // The divisions arithmetic never sees one, and cannot.
    //
    // `slash="yes"` is the whole of what an acciaccatura is on this side, where
    // LilyPond spells it with a different command. Written before the dynamic,
    // so a `<direction>` still lands on the note it belongs to rather than on
    // the ornament in front of it.
    //
    // Order within the element is the schema's too: grace, then the optional
    // chord flag, then the pitch, then the voice, then the note head.
    writeGraces { |leaf|
        if (leaf.hasGraces.not) { ^this };
        leaf.graces.do { |grace|
            var pitches = if (grace.isKindOf(Chord)) {
                grace.pitches
            } {
                [grace.pitch]
            };
            pitches.do { |pitch, i|
                stream << "      <note>\n        <grace"
                       << if (leaf.graceStyle == \acciaccatura) {
                           " slash=\"yes\""
                       } {
                           ""
                       }
                       << "/>\n";
                if (i > 0) { stream << "        <chord/>\n" };
                this.writePitch(pitch);
                if (currentVoice.notNil) {
                    stream << "        <voice>" << currentVoice << "</voice>\n"
                };
                stream << "        <type>"
                       << MusicXMLWriter.typeString(grace.dur) << "</type>\n";
                grace.dur.dots.do { stream << "        <dot/>\n" };
                stream << "      </note>\n";
            };
        };
        ^this
    }

    // Element order inside <note> is fixed by the MusicXML schema: duration,
    // then <tie>, then <voice>, then type, dots, time-modification, <beam>, and
    // <notations> last.
    writeBody { |leaf, tieStart = false, tieStop = false, isChordHead = true|
        var notations = List.new, marks;

        stream << "        <duration>" << this.ticks(leaf.prolatedDuration)
               << "</duration>\n";
        if (tieStop)  { stream << "        <tie type=\"stop\"/>\n" };
        if (tieStart) { stream << "        <tie type=\"start\"/>\n" };
        if (currentVoice.notNil) {
            stream << "        <voice>" << currentVoice << "</voice>\n"
        };

        stream << "        <type>" << MusicXMLWriter.typeString(leaf.dur) << "</type>\n";
        leaf.dur.dots.do { stream << "        <dot/>\n" };

        if (timeModification.notNil) {
            stream << "        <time-modification>\n"
                   << "          <actual-notes>" << timeModification[0]
                   << "</actual-notes>\n"
                   << "          <normal-notes>" << timeModification[1]
                   << "</normal-notes>\n"
                   << "        </time-modification>\n";
        };

        if (isChordHead) { this.writeBeams(leaf) };

        if (tieStop)  { notations.add("<tied type=\"stop\"/>") };
        if (tieStart) { notations.add("<tied type=\"start\"/>") };
        (tupletStarts[leaf] ? []).do { |number|
            notations.add(this.tupletTag(\start, number)) };
        (tupletStops[leaf] ? []).do { |number|
            notations.add(this.tupletTag(\stop, number)) };
        // Articulations join the same block rather than opening a second one: a
        // note that is tied, bracketed and accented still emits one
        // <notations>.
        if (isChordHead) {
            leaf.spannerStops.do { |endpoint|
                if (endpoint.isSlur) { notations.add(this.slurTag(endpoint)) }
            };
            leaf.spannerStarts.do { |endpoint|
                if (endpoint.isSlur) { notations.add(this.slurTag(endpoint)) }
            };
            marks = leaf.articulations;
            if (marks.notEmpty) {
                notations.add("<articulations>"
                    ++ marks.collect { |marking|
                        "<" ++ (articulationTags[marking.value]
                            ?? { Error("MusicXMLWriter: no tag for articulation %"
                                .format(marking.value)).throw }) ++ "/>"
                    }.join("")
                    ++ "</articulations>")
            }
        };
        this.writeNotations(notations);
    }

    // Beams, one element per level.
    //
    // LilyPond is told where a group begins and ends and works the rest out
    // from the note values. MusicXML is told nothing and has to be handed every
    // beam of every note. So this is where the two backends differ most, and
    // where a writer-side state machine earns its keep: the model carries two
    // endpoints, and the notes between them are this writer's problem.
    //
    // `<beam>` sits between `<time-modification>` and `<notations>` in the
    // schema's fixed order for `<note>`, which is why it is written here rather
    // than beside the slurs.
    //
    // The validator refuses a mixed group, so every note under one beam has the
    // same flag count. Every level then runs the whole length of the group and
    // says the same word. That is what makes this a state machine with one bit
    // in it rather than a beaming algorithm.
    writeBeams { |leaf|
        var value, levels;
        case
            { leaf.spannerStarts.any { |endpoint| endpoint.isBeam } }
                { value = "begin"; beamOpen = true }
            { leaf.spannerStops.any { |endpoint| endpoint.isBeam } }
                { value = "end"; beamOpen = false }
            { beamOpen } { value = "continue" }
            { true } { ^this };
        levels = leaf.dur.flags ? 1;
        levels.do { |i|
            stream << "        <beam number=\"" << (i + 1) << "\">" << value
                   << "</beam>\n"
        };
        ^this
    }

    // What goes inside <beats> for this meter.
    //
    // A grouped meter is one composite numerator over one denominator, which
    // MusicXML spells by putting the sum in the element: <beats>2+3</beats>.
    // The repeated-pair form is for a different thing: a composite of
    // *different* denominators, like 2/4 + 3/8, which this model cannot
    // express and so does not write.
    *beatsString { |meter|
        if (meter.isGrouped.not) { ^meter.count.asString };
        ^meter.groups.join("+")
    }

    // Numbered always: MusicXML's slur number is how a stop names its start,
    // and leaving it off only works while nothing overlaps.
    slurTag { |endpoint|
        if (endpoint.isSlur.not) {
            Error("MusicXMLWriter: no tag for a % spanner".format(endpoint.kind)).throw
        };
        ^"<slur type=\"" ++ endpoint.edge ++ "\" number=\"" ++ endpoint.id ++ "\"/>"
    }

    // Numbered always, for the same reason as slurs: nested stops can share one
    // note, and a stop has to say which start it closes.
    tupletTag { |type, number|
        ^"<tuplet type=\"" ++ type ++ "\" number=\"" ++ number ++ "\"/>"
    }

    // A hairpin is a wedge, and a wedge is a direction rather than a notation.
    // It belongs beside the notes it spans, not inside one of them. That is why
    // it is written here with the dynamics and not in the <notations> block
    // with the slurs.
    wedgeTag { |endpoint|
        var type = if (endpoint.isStart) { endpoint.direction } { \stop };
        if (endpoint.isHairpin.not) {
            Error("MusicXMLWriter: no wedge for a % spanner".format(endpoint.kind)).throw
        };
        ^"<wedge type=\"" ++ type ++ "\" number=\"" ++ endpoint.id ++ "\"/>"
    }

    // A dynamic is a direction, not a note property: it sits before the note it
    // applies to rather than inside it, which is also why it is written once
    // for a chord rather than once per notehead.
    writeDynamics { |leaf|
        leaf.dynamics.do { |marking|
            this.writeDirection("<dynamics><" ++ marking.value ++ "/></dynamics>")
        };
        // Text is a direction like a dynamic, and the one that carries its own
        // side of the staff rather than taking the default.
        leaf.texts.do { |marking|
            this.writeDirection(
                "<words>" ++ MusicXMLWriter.escape(marking.value) ++ "</words>",
                marking.placement)
        };
        // A wedge that closes comes before one that opens, so a hairpin can end
        // and another begin against the same note. Text spanners follow the
        // same order for the same reason.
        leaf.spannerStops.do { |endpoint|
            if (endpoint.isHairpin) { this.writeDirection(this.wedgeTag(endpoint)) };
            if (endpoint.isText) { this.writeTextSpanner(endpoint) };
        };
        leaf.spannerStarts.do { |endpoint|
            if (endpoint.isHairpin) { this.writeDirection(this.wedgeTag(endpoint)) };
            if (endpoint.isText) { this.writeTextSpanner(endpoint) };
        };
        ^this
    }

    // A text spanner is `<dashes>`, the dashed line, with the words beside
    // it. Both belong to one <direction>, as two <direction-type> elements:
    // they are one instruction, and importers that see two directions draw the
    // words and the line as unrelated things.
    //
    // The stop is dashes alone, with no placement, because it says nothing to
    // place: it cancels the instruction rather than repeating it.
    writeTextSpanner { |endpoint|
        stream << "      <direction";
        if (endpoint.isStart) { stream << " placement=\"" << endpoint.placement << "\"" };
        stream << ">\n";
        if (endpoint.isStart) {
            stream << "        <direction-type><words>"
                   << MusicXMLWriter.escape(endpoint.text)
                   << "</words></direction-type>\n"
        };
        stream << "        <direction-type><dashes type=\"" << endpoint.edge
               << "\" number=\"" << endpoint.id << "\"/></direction-type>\n";
        if (currentVoice.notNil) {
            stream << "        <voice>" << currentVoice << "</voice>\n"
        };
        stream << "      </direction>\n";
        ^this
    }

    // `<attributes>` when anything in it has changed, and nothing when nothing
    // has. Order inside the element is MusicXML's, not ours: divisions, time,
    // clef. An importer reading them out of order is entitled to refuse the
    // document, exactly as with `<offset>` inside a direction.
    //
    // The clef is here because it once was not: a staff carried one, LilyPond
    // honored it, ScoreJSON carried it, and this writer dropped it, so a bass
    // staff opened in treble wherever the document was read. Two writers only
    // catch that kind of divergence as well as the tests that compare them do.
    writeAttributes { |measure, opening|
        // The part opens in the staff's clef. A bar may change it after that.
        // Falling back to the staff's clef on any later bar would write a
        // change back to it after every change away from it.
        var clefHere = measure.clef ?? { if (opening) { staffClef } { nil } };
        var meterChanged = measure.meter != currentMeter;
        var clefChanged = clefHere.notNil and: { clefHere != currentClef };

        if (meterChanged.not and: { clefChanged.not }) { ^this };
        stream << "      <attributes>\n"
               << "        <divisions>" << divisions << "</divisions>\n";
        if (meterChanged) {
            currentMeter = measure.meter;
            stream << "        <time><beats>"
                   << MusicXMLWriter.beatsString(currentMeter)
                   << "</beats><beat-type>" << currentMeter.unit
                   << "</beat-type></time>\n";
        };
        if (clefChanged) {
            currentClef = clefHere;
            stream << "        " << MusicXMLWriter.clefTag(clefHere) << "\n";
        };
        stream << "      </attributes>\n";
        ^this
    }

    // A clef is a sign and the staff line it sits on, which is why the model
    // holds a closed vocabulary: there is nothing in the Symbol `\bass` to
    // derive `F` and `4` from.
    *clefTag { |clef|
        var pair = Staff.clefSigns[clef]
            ?? { Error("MusicXMLWriter: no clef spelling for %".format(
                clef.asCompileString)).throw };
        ^"<clef><sign>" ++ pair[0] ++ "</sign><line>" ++ pair[1] ++ "</line></clef>"
    }

    // What one <direction> says, as its <direction-type> elements in order.
    //
    // A rehearsal mark is <rehearsal> and nothing else. Everything else is
    // prose as <words>, and a tempo that carries a metronome mark adds
    // <metronome> beside it. That is one direction with two types, because
    // "Allegro (4 = 132)" is one instruction and an importer that met two
    // directions would draw the word and the number as unrelated marks.
    //
    // Prose alone arrives as <words> whatever kind it is. That is the loss
    // MusicXML has where LilyPond has none. Only the number says "this is a
    // tempo", and `writeDirection` writes it. See
    // Note [What each backend cannot say] in ScoreWriter.sc.
    *directionBodies { |direction|
        var out = List.new;
        if (direction.isRehearsalMark) {
            ^["<rehearsal>" ++ this.escape(direction.text) ++ "</rehearsal>"]
        };
        direction.text !? { |text|
            out.add("<words>" ++ this.escape(text) ++ "</words>")
        };
        if (direction.hasMetronome) { out.add(this.metronomeTag(direction)) };
        ^out.asArray
    }

    // <beat-unit>, then one <beat-unit-dot/> per dot, then the count. MusicXML
    // spells the unit as a note type exactly as a note head is spelled, which
    // is why this goes through the same `typeString` a leaf does. A dotted
    // quarter beat is a `quarter` with a dot, in both places.
    *metronomeTag { |direction|
        var dots = direction.unit.notation[1];
        ^"<metronome><beat-unit>" ++ this.typeString(direction.unit)
            ++ "</beat-unit>" ++ dots.collect { "<beat-unit-dot/>" }.join
            ++ "<per-minute>" ++ direction.perMinute
            ++ "</per-minute></metronome>"
    }

    // Below is where a dynamic and a hairpin sit by convention, so it stays the
    // default. Text says which side it wants, because that is part of what it
    // says. The two placements are the model's two, spelled as MusicXML's.
    // `<offset>` sits after the direction-type elements and before `<voice>`,
    // and `<sound>` after both, which is the order MusicXML declares, and
    // declared order is binding, as in `<attributes>` above. Omitted at zero,
    // which is where a direction stands unless it says otherwise, so an
    // ordinary document is the document it always was.
    //
    // `tempo` is what makes a tempo audible to an importer rather than merely
    // visible: <sound tempo="..."> is the playback half, counted in quarter
    // notes a minute, and it is the number the model already holds rather than
    // one guessed from a word.
    writeDirection { |body, placement = \below, offset, tempo|
        var bodies = if (body.isKindOf(String)) { [body] } { body };
        stream << "      <direction placement=\"" << placement << "\">\n";
        bodies.do { |each|
            stream << "        <direction-type>" << each << "</direction-type>\n"
        };
        offset !? {
            if (offset > Duration(0, 1)) {
                stream << "        <offset>" << this.ticks(offset) << "</offset>\n"
            }
        };
        if (currentVoice.notNil) {
            stream << "        <voice>" << currentVoice << "</voice>\n"
        };
        tempo !? {
            stream << "        <sound tempo=\""
                   << MusicXMLWriter.tempoString(tempo) << "\"/>\n"
        };
        stream << "      </direction>\n";
        ^this
    }

    // A whole speed prints whole: `120`, not `120.0`. The value is a rate and
    // so may be fractional (a dotted eighth note at 90 is 67.5 quarter notes a
    // minute), and MusicXML takes either, but a decimal point on every ordinary
    // tempo would be noise in the one place a reader looks for the number.
    *tempoString { |value|
        if (value.frac == 0) { ^value.asInteger.asString };
        ^value.asString
    }

    // One <notations> element per note, however many things it carries. A note
    // that both starts a tuplet and starts a tie would otherwise emit two,
    // which importers tolerate unevenly.
    writeNotations { |items|
        if (items.isEmpty) { ^this };
        stream << "        <notations>";
        items.do { |item| stream << item };
        stream << "</notations>\n";
        ^this
    }
}
