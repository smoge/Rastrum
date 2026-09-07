// MusicXMLWriter: MusicXML 4.0 partwise output.
//
// All MusicXML vocabulary lives here. Fragment output is for tests.
// `Rastrum.writeMusicXML` writes only full `MusicScore` documents.

MusicXMLWriter : ScoreWriter {
    // Divisions per quarter. `baseDivisions` is the floor.
    // `maxDivisions` keeps exact output importer-compatible.
    classvar <baseDivisions = 768;
    classvar <>maxDivisions = 65536;
    classvar <typeNames, <articulationTags, <dynamicTags, <technicalTags,
        <beamValues;

    var measureNumber, currentMeter, timeModification, tupletStarts, tupletStops;
    var tupletDepth;
    // Current indentation depth for helper-emitted XML lines.
    var depth;
    // Ties are keyed by voice because they may cross bars.
    var pendingTies, divisions, currentVoice, atPartStart, measureRests, beamRows;

    // Clef in force for the part; repeated only when it changes.
    var currentClef, staffClef;
    // Open tempo ramps by id and target.
    var openRamps;
    // Open glissandi by voice, with notehead count for chord
    // endpoints.
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
            // MusicXML names portato by the drawn tenuto-plus-staccato glyph.
            \portato -> "detached-legato",
            \breath -> "breath-mark", \caesura -> "caesura"
        ];
        // Technical marks use full elements because some spellings need children.
        technicalTags = IdentityDictionary[
            \upbow -> this.prVoid("up-bow"), \downbow -> this.prVoid("down-bow"),
            \stopped -> this.prVoid("stopped"),
            \snapPizzicato -> this.prVoid("snap-pizzicato"),
            // `<open/>` is another MusicXML mark.
            \openString -> this.prVoid("open-string"),
            // Natural harmonic only.
            \harmonic -> this.prWrap("harmonic", this.prVoid("natural"))
        ];
        // Ordinary dynamics are discrete tags.
        // Sforzandos are handled separately.
        dynamicTags = IdentityDictionary[
            \ppppp -> "ppppp",
            \pppp  -> "pppp",
            \ppp   -> "ppp",
            \pp    -> "pp",
            \p     -> "p",
            \mp    -> "mp",
            \mf    -> "mf",
            \f     -> "f",
            \ff    -> "ff",
            \fff   -> "fff",
            \ffff  -> "ffff",
            \fffff -> "fffff"
        ];

        // Beam row vocabulary. `AutoBeam` decides the state.
        beamValues = IdentityDictionary[
            \begin -> "begin",
            \continue -> "continue",
            \end -> "end",
            \forwardHook -> "forward hook",
            \backwardHook -> "backward hook"
        ];
    }

    prepare { |element|
        measureNumber = 0;
        // Structural tags set their own depth, so fragments do not inherit this.
        depth = 0;
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

    // Divisions per quarter needed for exact leaves and direction
    // offsets.
    //
    // A leaf of prolated duration d lasts d * 4 quarter notes.
    *divisionsFor { |element|
        var needed = baseDivisions;
        element.leaves.do { |leaf|
            needed = needed.lcm((leaf.prolatedDuration * Duration(4, 1)).denominator)
        };
        element.traverse { |node|
            if (node.isKindOf(Measure)) {
                node.directions.do { |direction|
                    needed = needed.lcm(
                        (direction.offset * Duration(4, 1)).denominator)
                }
            }
        };
        if (needed > maxDivisions) {
            Error(
                "MusicXMLWriter: counting this score exactly needs % divisions per "
                "quarter, above the % limit. Simplify tuplets or raise "
                "MusicXMLWriter.maxDivisions."
                .format(needed, maxDivisions)
            ).throw
        };
        ^needed
    }

    // Catch writer-owned spans still open at the document end.
    write { |element|
        var result;
        this.prRequireBeamableGroups(element);
        result = super.write(element);
        this.prRequireNoPendingTiesAnywhere("the music ends");
        this.prRequireNoOpenRampsAnywhere("the music ends");
        this.prRequireNoOpenGlissandiAnywhere("the music ends");
        ^result
    }

    // Exact to the integer: no float, no rounding.
    ticks { |dur|
        var exact = dur * Duration(4 * divisions, 1);
        if (exact.denominator != 1) {
            Error(
                "MusicXMLWriter: % is not a whole number of ticks at % divisions per "
                "quarter"
                .format(dur, divisions)
            ).throw
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
            Error(
                "MusicXMLWriter: % is not notatable as one leaf. Prepare the tree before writing."
                .format(dur)
            ).throw
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

    // Tag helpers centralize indentation and escaping; inline builders return
    // compact MusicXML fragments.

    prIndent { depth.do { stream << "  " }; ^this }

    // Builders are class-side so one-line vocabulary helpers spell tags consistently.

    *prAttrs { |pairs|
        var out = String.new;
        (pairs ? []).pairsDo { |key, value|
            out = out ++ " " ++ key ++ "=\"" ++ this.escape(value) ++ "\""
        };
        ^out
    }

    // >>> MusicXMLWriter.prElement("part-name", "a & b")
    // <part-name>a &amp; b</part-name>
    *prElement { |name, value, attrs|
        ^this.prWrap(name, this.escape(value), attrs)
    }

    // Inner markup is already MusicXML, so only attributes are escaped.
    //
    // >>> MusicXMLWriter.prWrap("work", MusicXMLWriter.prElement("work-title", "T"))
    // <work><work-title>T</work-title></work>
    *prWrap { |name, inner, attrs|
        ^"<" ++ name ++ this.prAttrs(attrs) ++ ">" ++ inner ++ "</" ++ name ++ ">"
    }

    // >>> MusicXMLWriter.prVoid("tied", ["type", "stop"])
    // <tied type="stop"/>
    *prVoid { |name, attrs| ^"<" ++ name ++ this.prAttrs(attrs) ++ "/>" }

    prAttrs   { |pairs|              ^MusicXMLWriter.prAttrs(pairs) }
    prElement { |name, value, attrs| ^MusicXMLWriter.prElement(name, value, attrs) }
    prWrap    { |name, inner, attrs| ^MusicXMLWriter.prWrap(name, inner, attrs) }
    prVoid    { |name, attrs|        ^MusicXMLWriter.prVoid(name, attrs) }

    prLine { |markup| this.prIndent; stream << markup << "\n"; ^this }

    prTag { |name, value, attrs| ^this.prLine(this.prElement(name, value, attrs)) }

    prEmptyTag { |name, attrs| ^this.prLine(this.prVoid(name, attrs)) }

    prOpenTag { |name, attrs|
        this.prLine("<" ++ name ++ this.prAttrs(attrs) ++ ">");
        depth = depth + 1;
        ^this
    }

    prCloseTag { |name| depth = depth - 1; ^this.prLine("</" ++ name ++ ">") }

    // Measure children keep the same depth in full scores and fragments.
    prOpenMeasureChild { |name, attrs| depth = 3; ^this.prOpenTag(name, attrs) }

    visitScore { |score|
        // XML declaration and DOCTYPE are not ordinary tags.
        stream << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
               << "<!DOCTYPE score-partwise PUBLIC "
               << "\"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" "
               << "\"http://www.musicxml.org/dtds/partwise.dtd\">\n";
        depth = 0;
        this.prOpenTag("score-partwise", ["version", "4.0"]);

        if (score.title.notNil or: { score.composer.notNil }) {
            this.prLine(this.prWrap("work",
                this.prElement("work-title", score.title ? "")));
            if (score.composer.notNil) {
                this.prLine(this.prWrap("identification",
                    this.prElement("creator", score.composer, ["type", "composer"])))
            };
        };

        this.prOpenTag("part-list");
        score.children.do { |staff, i|
            this.prOpenTag("score-part", ["id", "P" ++ (i + 1)]);
            // `<part-name>` is required; abbreviation is optional.
            this.prTag("part-name", staff.name ? "Part");
            staff.shortName !? { this.prTag("part-abbreviation", staff.shortName) };
            this.prCloseTag("score-part");
        };
        this.prCloseTag("part-list");

        // Parts do not share ties, tempo ramps or glissandi.
        score.children.do { |staff, i|
            this.prOpenTag("part", ["id", "P" ++ (i + 1)]);
            measureNumber = 0;
            atPartStart = true;
            currentMeter = nil;
            // The staff clef opens the part; bars may change it later.
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
            this.prCloseTag("part");
        };
        this.prCloseTag("score-partwise");
    }

    // Set `staffClef` for bare Staff output too.
    visitStaff { |staff| staffClef = staff.clef; this.writeChildren(staff) }

    visitMeasure { |measure|
        // Anacruses are implicit and share the previous bar number.
        // At part start that number is 0.
        var anacrusis = measure.isAnacrusis;
        var opening = atPartStart;
        var rampSounds, attrs;

        measureRests = IdentitySet.newFrom(measure.wholeBarRests);
        this.prRequirePlaceableMeasure(measure);
        if (anacrusis.not) { measureNumber = measureNumber + 1 };
        atPartStart = false;
        attrs = ["number", measureNumber];
        if (anacrusis) { attrs = attrs ++ ["implicit", "yes"] };
        depth = 2;
        this.prOpenTag("measure", attrs);
        this.writeAttributes(measure, opening);
        // Directions precede notes; mid-bar directions carry `<offset>`.
        // Voices rewind with `<backup>`.
        rampSounds = this.prRampSoundsIn(measure);
        measure.directions.do { |direction|
            this.writeDirection(MusicXMLWriter.directionBodies(direction),
                \above, direction.offset,
                this.prSoundTempoFor(direction, rampSounds))
        };
        if (measure.hasVoices) {
            measure.children.do { |voice, i|
                if (i > 0) {
                    this.prOpenMeasureChild("backup");
                    this.prTag("duration", this.ticks(measure.barDuration));
                    this.prCloseTag("backup");
                };
                currentVoice = i + 1;
                voice.accept(this);
            };
            currentVoice = nil;
        } {
            this.writeChildren(measure);
        };
        this.prCloseTag("measure");
    }

    visitTuplet { |tuplet|
        var saved = timeModification;
        var savedDepth = tupletDepth;
        var number = tupletDepth + 1;
        var leaves = tuplet.leaves;
        this.prRequireNonEmptyTuplet(tuplet);
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

    // MusicXML writes ties on both leaves; pending ties must land in
    // the next pitched leaf.
    visitNote { |note|
        var stopsHere = this.prConsumePendingTies([note.pitch], "a single note");
        this.writeGraces(note);
        this.writeDynamics(note);
        this.prOpenMeasureChild("note");
        this.writePitch(note.pitch);
        this.writeBody(note, note.tiesToNext, stopsHere.first);
        this.prCloseTag("note");
        pendingTies[this.prVoiceKey] = if (note.tiesToNext) { [note.pitch] } { [] };
    }

    visitRest { |rest|
        this.prRequireNoPendingTiesInVoice("a rest follows");
        if (measureRests.notNil and: { measureRests.includes(rest) }) {
            ^this.writeMeasureRest(rest)
        };
        this.writeGraces(rest);
        this.writeDynamics(rest);
        this.prOpenMeasureChild("note");
        this.prEmptyTag("rest");
        this.writeBody(rest);
        this.prCloseTag("note");
    }

    // MusicXML's whole-bar rest: exact duration, no type or dots.
    writeMeasureRest { |rest|
        this.prOpenMeasureChild("note");
        this.prEmptyTag("rest", ["measure", "yes"]);
        this.prTag("duration", this.ticks(rest.prolatedDuration));
        currentVoice !? { this.prTag("voice", currentVoice) };
        this.prCloseTag("note");
    }

    visitChord { |chord|
        var stops;
        this.prRequireNonEmptyChord(chord);
        stops = this.prConsumePendingTies(chord.pitches, "a chord");
        this.writeGraces(chord);
        this.writeDynamics(chord);
        chord.pitches.do { |p, i|
            this.prOpenMeasureChild("note");
            if (i > 0) { this.prEmptyTag("chord") };
            this.writePitch(p);
            // Chord-level notations are on the first `<note>`;
            // glissando is per notehead.
            this.writeBody(chord, chord.tiesToNext[i], stops[i], i == 0, i);
            this.prCloseTag("note");
        };
        pendingTies[this.prVoiceKey] = chord.tiedPitches;
    }

    // One Boolean per arriving pitch, true where a pending tie lands.
    prConsumePendingTies { |arriving, description|
        var key = this.prVoiceKey, pending = pendingTies[this.prVoiceKey] ?? { [] };
        var stops = arriving.collect { |p| pending.any { |waiting| waiting == p } };
        pending.do { |pending|
            if (arriving.any { |p| p == pending }.not) {
                Error(
                    "MusicXMLWriter: % ties to the next leaf, but % does not contain that pitch."
                    .format(pending, description)
                ).throw
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
            Error(
                "MusicXMLWriter: % ties to the next leaf, but %."
                .format(pending, situation)
            ).throw
        };
        ^this
    }

    // At the very end nothing may still be waiting, in any voice.
    prRequireNoPendingTiesAnywhere { |situation|
        pendingTies.keysValuesDo { |key, pending|
            if (pending.notEmpty) {
                Error(
                    "MusicXMLWriter: % ties onward in voice %, but %. A tie needs a following note."
                    .format(pending, key, situation)
                ).throw
            }
        };
        ^this
    }

    writePitch { |pitch|
        this.prOpenTag("pitch");
        this.prTag("step", pitch.letter.asString.toUpper);
        if (pitch.prAlter.isZero.not) {
            this.prTag("alter", pitch.prAlter.asFloat)
        };
        this.prTag("octave", pitch.octave);
        this.prCloseTag("pitch");
    }

    // Grace notes are separate `<note>` elements before the host and
    // carry no `<duration>`. `slash="yes"` marks acciaccatura.
    writeGraces { |leaf|
        if (leaf.hasGraces.not) { ^this };
        leaf.graces.do { |grace|
            var pitches = if (grace.isKindOf(Chord)) {
                grace.pitches
            } {
                [grace.pitch]
            };
            pitches.do { |pitch, i|
                this.prOpenMeasureChild("note");
                this.prEmptyTag("grace",
                    if (leaf.graceStyle == \acciaccatura) { ["slash", "yes"] });
                if (i > 0) { this.prEmptyTag("chord") };
                this.writePitch(pitch);
                currentVoice !? { this.prTag("voice", currentVoice) };
                this.prTag("type", MusicXMLWriter.typeString(grace.dur));
                grace.dur.dots.do { this.prEmptyTag("dot") };
                this.prCloseTag("note");
            };
        };
        ^this
    }

    // `<note>` child order is fixed by the MusicXML schema.
    writeBody { |leaf, tieStart = false, tieStop = false, isChordHead = true,
        noteIndex = 0|
        var notations = List.new, marks;

        this.prTag("duration", this.ticks(leaf.prolatedDuration));
        if (tieStop)  { this.prEmptyTag("tie", ["type", "stop"]) };
        if (tieStart) { this.prEmptyTag("tie", ["type", "start"]) };
        currentVoice !? { this.prTag("voice", currentVoice) };

        this.prTag("type", MusicXMLWriter.typeString(leaf.dur));
        leaf.dur.dots.do { this.prEmptyTag("dot") };

        if (timeModification.notNil) {
            this.prOpenTag("time-modification");
            this.prTag("actual-notes", timeModification[0]);
            this.prTag("normal-notes", timeModification[1]);
            this.prCloseTag("time-modification");
        };

        if (isChordHead) { this.writeBeams(leaf) };

        if (tieStop)  { notations.add(this.prVoid("tied", ["type", "stop"])) };
        if (tieStart) { notations.add(this.prVoid("tied", ["type", "start"])) };
        (tupletStarts[leaf] ? []).do { |number|
            notations.add(this.tupletTag(\start, number)) };
        (tupletStops[leaf] ? []).do { |number|
            notations.add(this.tupletTag(\stop, number)) };
        // Glissando is per notehead, so it stays outside the chord-head gate.
        this.glissandoTags(leaf, noteIndex).do { |tag| notations.add(tag) };
        // Collect all note-level notations into one block.
        if (isChordHead) {
            leaf.spannerStops.do { |endpoint|
                if (endpoint.isSlur) { notations.add(this.slurTag(endpoint)) }
            };
            leaf.spannerStarts.do { |endpoint|
                if (endpoint.isSlur) { notations.add(this.slurTag(endpoint)) }
            };
            // MusicXML writes fermata beside `<articulations>`.
            marks = leaf.articulations.reject { |marking|
                marking.value == \fermata };
            if (marks.notEmpty) {
                notations.add(this.prWrap("articulations",
                    marks.collect { |marking|
                        this.prVoid(articulationTags[marking.value]
                            ?? { Error("MusicXMLWriter: no tag for articulation %"
                                .format(marking.value)).throw })
                    }.join("")))
            };
            if (leaf.articulations.any { |marking| marking.value == \fermata }) {
                notations.add(this.prVoid("fermata"))
            };
            // MusicXML puts technical marks in their own block.
            if (leaf.technicals.notEmpty) {
                notations.add(this.prWrap("technical",
                    leaf.technicals.collect { |marking|
                        technicalTags[marking.value]
                            ?? { Error("MusicXMLWriter: no tag for technical %"
                                .format(marking.value)).throw }
                    }.join("")))
            }
        };
        this.writeNotations(notations);
    }

    // Beams, one element per level, placed in schema order.
    writeBeams { |leaf|
        var rows = beamRows[leaf];
        if (rows.isNil) { ^this };
        rows.do { |row|
            this.prTag("beam", MusicXMLWriter.beamValues[row[1]], ["number", row[0]])
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

    // Chord glissandi write one element per notehead; numbers pair endpoints.

    glissandoTags { |leaf, noteIndex|
        var glissandi = (leaf.spannerStops ++ leaf.spannerStarts).select {
            |endpoint| endpoint.isGlissando };
        if (glissandi.isEmpty) { ^[] };
        // Track once per leaf, not once per notehead.
        if (noteIndex == 0) { this.prTrackGlissandi(leaf, glissandi) };
        ^glissandi.collect { |endpoint|
            this.prVoid("glissando",
                ["type", endpoint.edge, "number", noteIndex + 1])
        }
    }

    // Stops close before starts open. Open entries are [id, notehead count].
    prTrackGlissandi { |leaf, glissandi|
        var key = this.prVoiceKey;
        var width = if (leaf.isKindOf(Chord)) { leaf.pitches.size } { 1 };
        glissandi.do { |endpoint|
            var open = openGlissandi[key];
            if (endpoint.isStart) {
                if (open.notNil) {
                    Error(
                        "MusicXMLWriter: glissando id % opens in voice % while id % is still open."
                        .format(endpoint.id, key, open[0])
                    ).throw
                };
                openGlissandi[key] = [endpoint.id, width]
            } {
                if (open.isNil) {
                    Error(
                        "MusicXMLWriter: glissando id % closes nothing in voice %."
                        .format(endpoint.id, key)
                    ).throw
                };
                if (endpoint.id != open[0]) {
                    Error(
                        "MusicXMLWriter: glissando id % closes in voice % where id % is open."
                        .format(endpoint.id, key, open[0])
                    ).throw
                };
                if (open[1] != width) {
                    Error(
                        "MusicXMLWriter: a glissando runs from % noteheads to %. Both ends need the same count."
                        .format(open[1], width)
                    ).throw
                };
                openGlissandi.removeAt(key);
            }
        };
        ^this
    }

    prRequireNoOpenGlissandiAnywhere { |situation|
        if (openGlissandi.notEmpty) {
            Error(
                "MusicXMLWriter: a glissando with id % is still open, but %. A "
                "glissando needs both ends in the same voice."
                .format(
                    openGlissandi.values.collect { |each| each[0] }.sort
                    .join(", "),
                    situation
                )
            ).throw
        };
        ^this
    }

    // Always numbered: a stop names its start by number.
    slurTag { |endpoint|
        if (endpoint.isSlur.not) {
            Error("MusicXMLWriter: no tag for a % spanner".format(endpoint.kind)).throw
        };
        ^this.prVoid("slur", ["type", endpoint.edge, "number", endpoint.id])
    }

    // Always numbered, for the same reason as slurs.
    tupletTag { |type, number|
        ^this.prVoid("tuplet", ["type", type, "number", number])
    }

    // A hairpin is a `<wedge>` direction, not a note notation.
    wedgeTag { |endpoint|
        var type = if (endpoint.isStart) { endpoint.direction } { \stop };
        if (endpoint.isHairpin.not) {
            Error(
                "MusicXMLWriter: no wedge for a % spanner".format(endpoint.kind)
            ).throw
        };
        ^this.prVoid("wedge", ["type", type, "number", endpoint.id])
    }

    // The ordinary-dynamic path. Sforzando spellings stay separate.
    //
    // >>> MusicXMLWriter.dynamicTag(\mf)   -> mf
    *dynamicTag { |value|
        ^dynamicTags[value] ?? {
            Error("MusicXMLWriter: no tag for dynamic %".format(value)).throw }
    }

    // `sfz` and `sffz` have native elements. Other words use
    // `<other-dynamics>`.
    //
    // >>> MusicXMLWriter.dynamicElement("sfz")    -> <sfz/>
    // >>> MusicXMLWriter.dynamicElement("smpz")   -> <other-dynamics>smpz</other-dynamics>
    *dynamicElement { |word|
        // `includes` compares Strings by identity, so this asks by value.
        if (["sfz", "sffz"].any { |each| each == word }) { ^this.prVoid(word) };
        ^this.prElement("other-dynamics", word)
    }

    // Dynamics are directions, written once for a chord.
    writeDynamics { |leaf|
        // A leaf holds at most one sforzando and one ordinary dynamic.
        var sforzando = MusicXMLWriter.sforzandoOf(leaf);
        var dynamic = MusicXMLWriter.dynamicOf(leaf);
        if (sforzando.notNil) {
            var inner = MusicXMLWriter.dynamicElement(
                Marking.sforzandoSpelling(sforzando.value));
            dynamic !? { inner = inner
                ++ this.prVoid(MusicXMLWriter.dynamicTag(dynamic.value)) };
            this.writeDirection(this.prWrap("dynamics", inner))
        } {
            dynamic !? {
                this.writeDirection(this.prWrap("dynamics",
                    this.prVoid(MusicXMLWriter.dynamicTag(dynamic.value))))
            };
        };
        // Text is a direction with its own placement.
        leaf.texts.do { |marking|
            this.writeDirection(this.prElement("words", marking.value),
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
        this.prOpenMeasureChild("direction",
            if (endpoint.isStart) { ["placement", endpoint.placement] });
        if (endpoint.isStart) {
            this.prLine(this.prWrap("direction-type",
                this.prElement("words", endpoint.text)))
        };
        this.prLine(this.prWrap("direction-type", this.prVoid("dashes",
            ["type", endpoint.edge, "number", endpoint.id])));
        currentVoice !? { this.prTag("voice", currentVoice) };
        this.prCloseTag("direction");
        ^this
    }

    // Write `<attributes>` only on meter or clef change, in schema order.
    writeAttributes { |measure, opening|
        // The part opens in the staff clef. Later bars change only explicitly.
        var clefHere = measure.clef ?? { if (opening) { staffClef } { nil } };
        var meterChanged = measure.meter != currentMeter;
        var clefChanged = clefHere.notNil and: { clefHere != currentClef };

        if (meterChanged.not and: { clefChanged.not }) { ^this };
        this.prOpenMeasureChild("attributes");
        this.prTag("divisions", divisions);
        if (meterChanged) {
            currentMeter = measure.meter;
            this.prLine(this.prWrap("time",
                this.prElement("beats", MusicXMLWriter.beatsString(currentMeter))
                    ++ this.prElement("beat-type", currentMeter.unit)));
        };
        if (clefChanged) {
            currentClef = clefHere;
            this.prLine(MusicXMLWriter.clefTag(clefHere));
        };
        this.prCloseTag("attributes");
        ^this
    }

    // A clef is a sign and staff line.
    *clefTag { |clef|
        var pair = Staff.clefSigns[clef]
            ?? { Error("MusicXMLWriter: no clef spelling for %".format(
                clef.asCompileString)).throw };
        ^this.prWrap("clef", this.prElement("sign", pair[0])
            ++ this.prElement("line", pair[1]))
    }

    // One <direction> may hold several <direction-type> bodies.
    *writesTempoRamps { ^true }

    // >>> MusicXMLWriter.directionBodies(Direction.tempoRampStart("rit.", beat: "4", bpm: 60)).asCompileString
    // ["<words>rit.</words>", "<dashes type=\"start\" number=\"1\"/>"]
    // >>> MusicXMLWriter.directionBodies(Direction.tempoRampStop).asCompileString
    // ["<dashes type=\"stop\" number=\"1\"/>"]
    *directionBodies { |direction|
        var out = List.new;
        this.prRequireWritableDirection(direction);
        if (direction.isTempoRamp) {
            direction.text !? { |text| out.add(this.prElement("words", text)) };
            out.add(this.prVoid("dashes",
                ["type", direction.edge, "number", direction.id]));
            ^out.asArray
        };
        if (direction.isRehearsalMark) {
            ^[this.prElement("rehearsal", direction.text)]
        };
        direction.text !? { |text| out.add(this.prElement("words", text)) };
        if (direction.hasMetronome) { out.add(this.metronomeTag(direction)) };
        ^out.asArray
    }

    // <beat-unit>, dots, then the count.
    //
    // >>> MusicXMLWriter.metronomeTag(Direction.metronome(Duration.quarter, 120)).asCompileString
    // "<metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome>"
    *metronomeTag { |direction|
        var dots = direction.unit.notation[1];
        ^this.prWrap("metronome",
            this.prElement("beat-unit", this.typeString(direction.unit))
                ++ dots.collect { this.prVoid("beat-unit-dot") }.join
                ++ this.prElement("per-minute", direction.perMinute))
    }

    // Per-bar `<sound>` tempo targets for ramp stops.
    prRampSoundsIn { |measure|
        var sounds = IdentityDictionary.new;
        var ramps = measure.directions.select { |each| each.isTempoRamp };

        Validator.inSpanOrder(ramps).do { |direction|
            if (direction.isRampStart) {
                // Same overlap rule as `Validator`.
                if (openRamps.notEmpty) {
                    Error(
                        "MusicXMLWriter: a tempo ramp is already open when the ramp "
                        "with id % starts. Close the first ramp before opening another."
                        .format(direction.id)
                    ).throw
                };
                // Store the endpoint; the stop may need its target.
                openRamps[direction.id] = direction;
            } {
                if (openRamps.includesKey(direction.id).not) {
                    Error(
                        "MusicXMLWriter: a tempo ramp stop with id % closes nothing."
                        .format(direction.id)
                    ).throw
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

    // Point tempo says speed directly. Ramp endpoint uses the pre-pass.
    prSoundTempoFor { |direction, sounds|
        if (direction.isTempoRamp) { ^sounds[direction] };
        ^direction.quarterPerMinute
    }

    // A ramp crosses bars, never parts.
    prRequireNoOpenRampsAnywhere { |situation|
        if (openRamps.notEmpty) {
            Error(
                "MusicXMLWriter: a tempo ramp with id % is still open, but %. A ramp "
                "needs both ends in the same part."
                .format(openRamps.keys.asArray.sort.join(", "), situation)
            ).throw
        };
        ^this
    }

    writeDirection { |body, placement = \below, offset, tempo|
        var bodies = if (body.isKindOf(String)) { [body] } { body };
        this.prOpenMeasureChild("direction", ["placement", placement]);
        bodies.do { |each| this.prLine(this.prWrap("direction-type", each)) };
        offset !? {
            if (offset > Duration(0, 1)) {
                this.prTag("offset", this.ticks(offset))
            }
        };
        currentVoice !? { this.prTag("voice", currentVoice) };
        tempo !? {
            this.prEmptyTag("sound", ["tempo", MusicXMLWriter.tempoString(tempo)])
        };
        this.prCloseTag("direction");
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
        this.prLine(this.prWrap("notations", items.join("")));
        ^this
    }
}
