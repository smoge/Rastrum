// MusicXMLWriter: MusicXML 4.0 partwise output.
//
// All MusicXML vocabulary lives in this file, and no external binary
// is needed. MusicXML restates what LilyPond infers, so this writer
// tracks clef, meter, ties, beam rows and divisions.


// Note [Only a score is a document]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `visitScore` writes the DOCTYPE, `<score-partwise>` and
// `<part-list>`. Fragments are useful for assertions, but not full
// documents. `Rastrum.writeMusicXML` refuses to put such fragments on
// disk.

MusicXMLWriter : ScoreWriter {
    // MusicXML counts time in divisions per quarter. The score
    // chooses a value large enough to count every duration exactly.
    //
    // `baseDivisions` is only a floor.
    //
    // `maxDivisions` is the practical ceiling for importer compatibility.
    classvar <baseDivisions = 768;
    classvar <>maxDivisions = 65536;
    classvar <typeNames, <articulationTags, <dynamicTags, <technicalTags,
        <beamValues;

    var measureNumber, currentMeter, timeModification, tupletStarts, tupletStops;
    var tupletDepth;
    var pendingTies, divisions, currentVoice, atPartStart, measureRests, beamRows;

    // Clef in force for the part, tracked like meter to avoid noisy restatement.
    var currentClef, staffClef;
    // Open tempo ramps by id and target.
    var openRamps;
    // See Note [A glissando is numbered per notehead].
    var openGlissandi;

    *initClass {
        typeNames = IdentityDictionary[
            2 -> "half", 4 -> "quarter", 8 -> "eighth", 16 -> "16th",
            32 -> "32nd", 64 -> "64th", 128 -> "128th", 256 -> "256th",
            512 -> "512th", 1024 -> "1024th"
        ];
        // MusicXML spells marcato as `<strong-accent/>`.
        articulationTags = IdentityDictionary[
            \staccato -> "staccato", \staccatissimo -> "staccatissimo",
            \tenuto -> "tenuto", \accent -> "accent", \marcato -> "strong-accent",
            // MusicXML names the drawn tenuto-plus-staccato glyph.
            \portato -> "detached-legato",
            \breath -> "breath-mark", \caesura -> "caesura"
        ];
        // MusicXML's `<technical>` split matches the model split.
        // See Note [A technical mark is not an articulation] in Marking.sc.
        // Whole elements: `harmonic` has a child, so this is not tag-name data.
        technicalTags = IdentityDictionary[
            \upbow -> "<up-bow/>", \downbow -> "<down-bow/>",
            \stopped -> "<stopped/>",
            \snapPizzicato -> "<snap-pizzicato/>",
            // `<open/>` is a different mark.
            \openString -> "<open-string/>",
            // Natural harmonic, not the wider MusicXML family.
            \harmonic -> "<harmonic><natural/></harmonic>"
        ];
        // One row per dynamic, read rather than interpolated.
        // See Note [A dynamic is pinned, not interpolated] in LilyWriter.sc.
        dynamicTags = IdentityDictionary[
            \ppppp -> "ppppp", \pppp -> "pppp", \ppp -> "ppp", \pp -> "pp",
            \p -> "p", \mp -> "mp", \mf -> "mf", \f -> "f", \ff -> "ff",
            \fff -> "fff", \ffff -> "ffff", \fffff -> "fffff"
        ];
        // MusicXML's beam row vocabulary; `AutoBeam` decides the state.
        beamValues = IdentityDictionary[
            \begin -> "begin", \continue -> "continue", \end -> "end",
            \forwardHook -> "forward hook", \backwardHook -> "backward hook"
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
        beamRows = AutoBeam.rowsIn(element);
        currentClef = nil;
        staffClef = nil;
        openRamps = Dictionary.new;
        openGlissandi = Dictionary.new;
        divisions = MusicXMLWriter.divisionsFor(element);
        ^this
    }

    // Divisions per quarter needed to count this score exactly.
    //
    // A leaf of prolated duration d lasts d * 4 quarter notes. The
    // answer is the LCM of those denominators and the base.
    *divisionsFor { |element|
        var needed = baseDivisions;
        element.leaves.do { |leaf|
            needed = needed.lcm((leaf.prolatedDuration * Duration(4, 1)).denominator)
        };
        // Direction offsets use the same quarter-note divisions as notes.
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
                "per quarter, above the % limit. Simplify tuplets or raise "
                "MusicXMLWriter.maxDivisions.".format(
                    needed, maxDivisions)).throw
        };
        ^needed
    }

    // Catch ties, tempo ramps and glissandi still open at the document end.
    write { |element|
        var result = super.write(element);
        this.prRequireNoPendingTiesAnywhere("the music ends");
        this.prRequireNoOpenRampsAnywhere("the music ends");
        this.prRequireNoOpenGlissandiAnywhere("the music ends");
        ^result
    }

    // Exact to the integer: no float, no rounding.
    ticks { |dur|
        var exact = dur * Duration(4 * divisions, 1);
        if (exact.denominator != 1) {
            Error("MusicXMLWriter: % is not a whole number of ticks at % "
                "divisions per quarter".format(dur, divisions)).throw
        };
        ^exact.numerator
    }

    // The undotted value as MusicXML names it. Dots are separate `<dot/>` tags.
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
            Error("MusicXMLWriter: % is not notatable as one leaf. Prepare the "
                "tree before writing.".format(dur)).throw
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
                   << "</part-name>\n";
            // `<part-name>` is required; abbreviation is optional.
            staff.shortName !? {
                stream << "      <part-abbreviation>"
                       << MusicXMLWriter.escape(staff.shortName)
                       << "</part-abbreviation>\n"
            };
            stream << "    </score-part>\n";
        };
        stream << "  </part-list>\n";

        // Each part starts and ends clean. Ties and tempo ramps do not carry
        // from one part into the next.
        score.children.do { |staff, i|
            stream << "  <part id=\"P" << (i + 1) << "\">\n";
            measureNumber = 0;
            atPartStart = true;
            currentMeter = nil;
            // The staff's clef opens the part; bars may change it later.
            staffClef = staff.clef;
            currentClef = nil;
            pendingTies = Dictionary.new;
            openRamps = Dictionary.new;
            openGlissandi = Dictionary.new;
            staff.accept(this);
            this.prRequireNoPendingTiesAnywhere(
                "part % ends".format(i + 1));
            this.prRequireNoOpenRampsAnywhere("part % ends".format(i + 1));
            this.prRequireNoOpenGlissandiAnywhere("part % ends".format(i + 1));
            stream << "  </part>\n";
        };
        stream << "</score-partwise>\n";
    }

    // Set `staffClef` for bare Staff output too.
    visitStaff { |staff| staffClef = staff.clef; this.writeChildren(staff) }

    visitMeasure { |measure|
        // A pickup is measure 0 and `implicit`. It must begin the part and end
        // at the barline.
        var pickup = atPartStart and: { measure.isAnacrusis };
        // Capture before `atPartStart` is cleared.
        var opening = atPartStart;
        var rampSounds;

        measureRests = IdentitySet.newFrom(measure.wholeBarRests);
        this.prRequirePlaceableMeasure(measure);
        if (pickup.not) { measureNumber = measureNumber + 1 };
        atPartStart = false;
        stream << "    <measure number=\"" << if (pickup) { 0 } { measureNumber } << "\"";
        if (pickup) { stream << " implicit=\"yes\"" };
        stream << ">\n";
        this.writeAttributes(measure, opening);
        // Directions stay before the bar's notes. Mid-bar directions carry
        // <offset>; voices rewind with <backup>.
        rampSounds = this.prRampSoundsIn(measure);
        measure.directions.do { |direction|
            this.writeDirection(MusicXMLWriter.directionBodies(direction),
                \above, direction.offset,
                this.prSoundTempoFor(direction, rampSounds))
        };
        if (measure.hasVoices) {
            measure.children.do { |voice, i|
                if (i > 0) {
                    stream << "      <backup>\n        <duration>"
                           << this.ticks(measure.barDuration)
                           << "</duration>\n      </backup>\n";
                };
                currentVoice = i + 1;
                voice.accept(this);
            };
            currentVoice = nil;
        } {
            this.writeChildren(measure);
        };
        stream << "    </measure>\n";
    }

    // `pendingTies` is per voice and may cross bars.
    visitVoice { |voice| this.writeChildren(voice) }

    visitTuplet { |tuplet|
        var saved = timeModification;
        var savedDepth = tupletDepth;
        var number = tupletDepth + 1;
        var leaves = tuplet.leaves;
        if (tuplet.isTrivial) { ^this.writeChildren(tuplet) };
        // Accumulate written counts, not reduced multipliers.
        timeModification = if (saved.isNil) {
            [tuplet.actualNotes, tuplet.normalNotes]
        } {
            [saved[0] * tuplet.actualNotes, saved[1] * tuplet.normalNotes]
        };
        // Numbered by depth: starts outer-to-inner, stops inner-to-outer.
        tupletDepth = number;
        if (leaves.notEmpty) {
            tupletStarts[leaves.first] = (tupletStarts[leaves.first] ? []) ++ [number];
            tupletStops[leaves.last] = [number] ++ (tupletStops[leaves.last] ? []);
        };
        this.writeChildren(tuplet);
        tupletDepth = savedDepth;
        timeModification = saved;
    }

    // MusicXML writes ties on both leaves. `pendingTies` records pitches that
    // must arrive in the next pitched leaf.
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

    // MusicXML's whole-bar rest: exact duration, no type or dots.
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
            // Only the first <note> carries chord-level notations, but a
            // glissando is per notehead. See Note [A glissando is numbered per
            // notehead].
            this.writeBody(chord, chord.tiesToNext[i], stops[i], i == 0, i);
            stream << "      </note>\n";
        };
        pendingTies[this.prVoiceKey] = chord.tiedPitches;
    }

    // One Boolean per arriving pitch, true where a pending tie lands.
    prConsumePendingTies { |arriving, description|
        var key = this.prVoiceKey, pending = pendingTies[this.prVoiceKey] ?? { [] };
        var stops = arriving.collect { |p| pending.any { |waiting| waiting == p } };
        pending.do { |pending|
            if (arriving.any { |p| p == pending }.not) {
                Error("MusicXMLWriter: % ties to the next leaf, but % does not "
                    "contain that pitch.".format(
                        pending, description)).throw
            }
        };
        pendingTies[key] = [];
        ^stops
    }

    // Which timeline's pending ties we are in.
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
                Error("MusicXMLWriter: % ties onward in voice %, but %. A tie "
                    "needs a following note.".format(pending, key, situation)).throw
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

    // Grace notes are separate `<note>` elements before the host and carry no
    // `<duration>`. `slash="yes"` marks acciaccatura.
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

    // Element order inside <note> is fixed by the MusicXML schema.
    writeBody { |leaf, tieStart = false, tieStop = false, isChordHead = true,
        noteIndex = 0|
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
        // Every notehead carries its own line, so this is outside the
        // chord-head gate. See Note [A glissando is numbered per notehead].
        this.glissandoTags(leaf, noteIndex).do { |tag| notations.add(tag) };
        // Collect all note-level notations into one block.
        if (isChordHead) {
            leaf.spannerStops.do { |endpoint|
                if (endpoint.isSlur) { notations.add(this.slurTag(endpoint)) }
            };
            leaf.spannerStarts.do { |endpoint|
                if (endpoint.isSlur) { notations.add(this.slurTag(endpoint)) }
            };
            // Note [Where a fermata goes]
            // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            //
            // The model treats fermata as an articulation; MusicXML writes
            // `<fermata/>` beside `<articulations>`.
            marks = leaf.articulations.reject { |marking|
                marking.value == \fermata };
            if (marks.notEmpty) {
                notations.add("<articulations>"
                    ++ marks.collect { |marking|
                        "<" ++ (articulationTags[marking.value]
                            ?? { Error("MusicXMLWriter: no tag for articulation %"
                                .format(marking.value)).throw }) ++ "/>"
                    }.join("")
                    ++ "</articulations>")
            };
            if (leaf.articulations.any { |marking| marking.value == \fermata }) {
                notations.add("<fermata/>")
            };
            // MusicXML puts technical marks in their own block.
            if (leaf.technicals.notEmpty) {
                notations.add("<technical>"
                    ++ leaf.technicals.collect { |marking|
                        technicalTags[marking.value]
                            ?? { Error("MusicXMLWriter: no tag for technical %"
                                .format(marking.value)).throw }
                    }.join("")
                    ++ "</technical>")
            }
        };
        this.writeNotations(notations);
    }

    // Beams, one element per level, placed in schema order.
    writeBeams { |leaf|
        var rows = beamRows[leaf];
        if (rows.isNil) { ^this };
        rows.do { |row|
            stream << "        <beam number=\"" << row[0] << "\">"
                   << MusicXMLWriter.beamValues[row[1]] << "</beam>\n"
        };
        ^this
    }

    // What goes inside <beats> for this meter.
    //
    // A grouped meter is one composite numerator over one
    // denominator: `<beats>2+3</beats>`.
    *beatsString { |meter|
        if (meter.isGrouped.not) { ^meter.count.asString };
        ^meter.groups.join("+")
    }

    // Note [A glissando is numbered per notehead]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // MusicXML draws one `<glissando>` per line. A chord glissando
    // writes one element on each notehead. Numbers pair endpoints by
    // notehead position. `Validator` checks the same count, and the
    // raw writer checks again.

	glissandoTags { |leaf, noteIndex|
        var glissandi = (leaf.spannerStops ++ leaf.spannerStarts).select {
            |endpoint| endpoint.isGlissando };
        if (glissandi.isEmpty) { ^[] };
        // Track once per leaf, not once per notehead.
        if (noteIndex == 0) { this.prTrackGlissandi(leaf, glissandi) };
        ^glissandi.collect { |endpoint|
            "<glissando type=\"" ++ endpoint.edge ++ "\" number=\""
                ++ (noteIndex + 1) ++ "\"/>"
        }
    }

    // Stops come first, so a chain closes one line before opening the
    // next. The open entry is [id, notehead count]: the id is what a
    // stop pairs with, and the count is what the numbering needs both
    // ends to agree on.
    prTrackGlissandi { |leaf, glissandi|
        var key = this.prVoiceKey;
        var width = if (leaf.isKindOf(Chord)) { leaf.pitches.size } { 1 };
        glissandi.do { |endpoint|
            var open = openGlissandi[key];
            if (endpoint.isStart) {
                if (open.notNil) {
                    Error("MusicXMLWriter: glissando id % opens in voice % "
                        "while id % is still open."
                        .format(endpoint.id, key, open[0])).throw
                };
                openGlissandi[key] = [endpoint.id, width]
            } {
                if (open.isNil) {
                    Error("MusicXMLWriter: glissando id % closes nothing in "
                        "voice %.".format(endpoint.id, key)).throw
                };
                if (endpoint.id != open[0]) {
                    Error("MusicXMLWriter: glissando id % closes in voice % "
                        "where id % is open.".format(
                            endpoint.id, key, open[0])).throw
                };
                if (open[1] != width) {
                    Error("MusicXMLWriter: a glissando runs from % noteheads "
                        "to %. Both ends need the same count."
                        .format(open[1], width)).throw
                };
                openGlissandi.removeAt(key);
            }
        };
        ^this
    }

    prRequireNoOpenGlissandiAnywhere { |situation|
        if (openGlissandi.notEmpty) {
            Error("MusicXMLWriter: a glissando with id % is still open, but %. "
                "A glissando needs both ends in the same voice.".format(
                    openGlissandi.values.collect { |each| each[0] }.sort
                        .join(", "),
                    situation)).throw
        };
        ^this
    }

    // Always numbered: a stop names its start by number.
    slurTag { |endpoint|
        if (endpoint.isSlur.not) {
            Error("MusicXMLWriter: no tag for a % spanner".format(endpoint.kind)).throw
        };
        ^"<slur type=\"" ++ endpoint.edge ++ "\" number=\"" ++ endpoint.id ++ "\"/>"
    }

    // Always numbered, for the same reason as slurs.
    tupletTag { |type, number|
        ^"<tuplet type=\"" ++ type ++ "\" number=\"" ++ number ++ "\"/>"
    }

    // A hairpin is a `<wedge>` direction, not a note notation.
    wedgeTag { |endpoint|
        var type = if (endpoint.isStart) { endpoint.direction } { \stop };
        if (endpoint.isHairpin.not) {
            Error("MusicXMLWriter: no wedge for a % spanner".format(endpoint.kind)).throw
        };
        ^"<wedge type=\"" ++ type ++ "\" number=\"" ++ endpoint.id ++ "\"/>"
    }

    // `sfz` and `sffz` have native elements; other sforzandos use
    // `<other-dynamics>`.
    //
    // >>> MusicXMLWriter.dynamicElement("sfz")    -> <sfz/>
    // >>> MusicXMLWriter.dynamicElement("smpz")   -> <other-dynamics>smpz</other-dynamics>
    // The ordinary-dynamic path; sforzando spellings stay separate.
    //
    // >>> MusicXMLWriter.dynamicTag(\mf)   -> mf
    *dynamicTag { |value|
        ^dynamicTags[value] ?? {
            Error("MusicXMLWriter: no tag for dynamic %".format(value)).throw }
    }

    *dynamicElement { |word|
        // `includes` compares Strings by identity, so this asks by value.
        if (["sfz", "sffz"].any { |each| each == word }) { ^"<" ++ word ++ "/>" };
        ^"<other-dynamics>" ++ MusicXMLWriter.escape(word) ++ "</other-dynamics>"
    }

    // A dynamic is a direction, written once for a chord.
    writeDynamics { |leaf|
        // A sforzando plus dynamic is one `<dynamics>` block. Two of
        // either is the last written.
        var sforzando = MusicXMLWriter.sforzandoOf(leaf);
        var dynamic = MusicXMLWriter.dynamicOf(leaf);
        if (sforzando.notNil) {
            var inner = MusicXMLWriter.dynamicElement(
                Marking.sforzandoSpelling(sforzando.value));
            dynamic !? { inner = inner
                ++ "<" ++ MusicXMLWriter.dynamicTag(dynamic.value) ++ "/>" };
            this.writeDirection("<dynamics>" ++ inner ++ "</dynamics>")
        } {
            dynamic !? {
                this.writeDirection("<dynamics><"
                    ++ MusicXMLWriter.dynamicTag(dynamic.value)
                    ++ "/></dynamics>")
            };
        };
        // Text is a direction with its own placement.
        leaf.texts.do { |marking|
            this.writeDirection(
                "<words>" ++ MusicXMLWriter.escape(marking.value) ++ "</words>",
                marking.placement)
        };
        // Stops before starts at one note.
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

    // Text spanner start: words plus `<dashes>` in one direction.
    // Stop: dashes alone.
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

    // Write `<attributes>` only on meter or clef change, in schema order.
    writeAttributes { |measure, opening|
        // The part opens in the staff clef; later bars change only explicitly.
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

    // A clef is a sign and staff line.
    *clefTag { |clef|
        var pair = Staff.clefSigns[clef]
            ?? { Error("MusicXMLWriter: no clef spelling for %".format(
                clef.asCompileString)).throw };
        ^"<clef><sign>" ++ pair[0] ++ "</sign><line>" ++ pair[1] ++ "</line></clef>"
    }

    // One <direction> may hold several <direction-type> bodies.
    *writesTempoRamps { ^true }

    // >>> MusicXMLWriter.directionBodies(
    //     Direction.tempoRampStart("rit.", beat: "4", bpm: 60)).asCompileString
    // ["<words>rit.</words>", "<dashes type=\"start\" number=\"1\"/>"]
    // >>> MusicXMLWriter.directionBodies(Direction.tempoRampStop).asCompileString
    // ["<dashes type=\"stop\" number=\"1\"/>"]
    *directionBodies { |direction|
        var out = List.new;
        this.prRequireWritableDirection(direction);
        if (direction.isTempoRamp) {
            direction.text !? { |text|
                out.add("<words>" ++ this.escape(text) ++ "</words>")
            };
            out.add("<dashes type=\"" ++ direction.edge ++ "\" number=\""
                ++ direction.id ++ "\"/>");
            ^out.asArray
        };
        if (direction.isRehearsalMark) {
            ^["<rehearsal>" ++ this.escape(direction.text) ++ "</rehearsal>"]
        };
        direction.text !? { |text|
            out.add("<words>" ++ this.escape(text) ++ "</words>")
        };
        if (direction.hasMetronome) { out.add(this.metronomeTag(direction)) };
        ^out.asArray
    }

    // <beat-unit>, dots, then the count.
    //
    // >>> MusicXMLWriter.metronomeTag(Direction.metronome(Duration.quarter, 120))
    //     .asCompileString
    // "<metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome>"
    *metronomeTag { |direction|
        var dots = direction.unit.notation[1];
        ^"<metronome><beat-unit>" ++ this.typeString(direction.unit)
            ++ "</beat-unit>" ++ dots.collect { "<beat-unit-dot/>" }.join
            ++ "<per-minute>" ++ direction.perMinute
            ++ "</per-minute></metronome>"
    }

    // Per-bar ramp targets for `<sound>`. Starts store targets; stops spend
    // them unless a written tempo stands there.
    prRampSoundsIn { |measure|
        var sounds = IdentityDictionary.new;
        var ramps = measure.directions.select { |each| each.isTempoRamp };

        Validator.inSpanOrder(ramps).do { |direction|
            if (direction.isRampStart) {
                // Same overlap rule as `Validator`.
                if (openRamps.notEmpty) {
                    Error("MusicXMLWriter: a tempo ramp is already open when the "
                        "ramp with id % starts. Close the first ramp before "
                        "opening another.".format(direction.id)).throw
                };
                // Store the endpoint, not only its target.
                openRamps[direction.id] = direction;
            } {
                if (openRamps.includesKey(direction.id).not) {
                    Error("MusicXMLWriter: a tempo ramp stop with id % closes "
                        "nothing.".format(direction.id)).throw
                };
                // nil means no `<sound>`: no target, or written tempo wins.
                sounds[direction] =
                    openRamps.removeAt(direction.id).quarterPerMinute !? { |target|
                        if (measure.directions.any { |each|
                            each.hasMetronome and: { each.offset == direction.offset }
                        }) { nil } { target }
                    }
            }
        };
        ^sounds
    }

    // Point tempo says speed directly; ramp endpoint uses the pre-pass.
    prSoundTempoFor { |direction, sounds|
        if (direction.isTempoRamp) { ^sounds[direction] };
        ^direction.quarterPerMinute
    }

    // A ramp crosses bars, never parts.
    prRequireNoOpenRampsAnywhere { |situation|
        if (openRamps.notEmpty) {
            Error("MusicXMLWriter: a tempo ramp with id % is still open, but %. "
                "A ramp needs both ends in the same part.".format(
                    openRamps.keys.asArray.sort.join(", "), situation)).throw
        };
        ^this
    }

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

    // Whole speed prints whole: `120`, not `120.0`.
    //
    // >>> MusicXMLWriter.tempoString(120.0).asCompileString   -> "120"
    // >>> MusicXMLWriter.tempoString(67.5).asCompileString    -> "67.5"
    *tempoString { |value|
        if (value.frac == 0) { ^value.asInteger.asString };
        ^value.asString
    }

    // One <notations> element per note.
    writeNotations { |items|
        if (items.isEmpty) { ^this };
        stream << "        <notations>";
        items.do { |item| stream << item };
        stream << "</notations>\n";
        ^this
    }
}
