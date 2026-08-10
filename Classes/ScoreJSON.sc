// ScoreJSONWriter / ScoreJSONReader: the neutral interchange format.
//
// This is a tagged tree with exact rationals as [numerator, denominator] pairs,
// and no floats anywhere in the wire format except `cents`, which is a
// deviation and not a duration.
//
//   {"format":"rastrum-score","version":18,
//    "type":"score","title":...,"composer":...,"staves":[
//      {"type":"staff","name":...,"clef":"treble","measures":[
//        {"type":"measure","meter":[4,4],"elements":[
//          {"type":"note","pitch":{"step":0,"alter":[1,2],"octave":4,"cents":0.0},
//           "duration":[1,4],"tiesToNext":true},
//          {"type":"rest","duration":[1,8]},
//          {"type":"chord","pitches":[...],"duration":[1,2],
//           "tiesToNext":[true,false,true]},
//          {"type":"tuplet","multiplier":[2,3],"elements":[...]}]}]}]}
//
// The tags are deliberately regular: a top-level format/version pair plus a
// "type" discriminator on each tree node.


// The version history is docs/interchange.md: what each version added, and
// what it costs a reader of the one before. Kept there rather than here
// because it is a record for whoever is reading a document, and because
// metasonic-score has to read the same list.
ScoreJSONWriter : ScoreWriter {

    // The wire contract, in one place, so writer and reader cannot disagree
    // about it and a bump is a single edit.
    classvar <schemaFormat = "rastrum-score";
    classvar <schemaVersion = 18;

    // A document is a whole score, because the format and version envelope is
    // written by visitScore and by nothing else. A bare Measure would be a
    // fragment the reader then refuses, so it is refused here instead of left
    // on disk unreadable. Giving other roots an envelope is a schema decision
    // nobody has made.
    //
    // >>> { |s| ScoreJSONReader.read(ScoreJSONWriter.new.write(s))
    //     .duration == s.duration }
    //     .value(MusicScore([Staff([
    //         RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1])])]))
    // true
    // >>> try { ScoreJSONWriter.new.write(Measure(Meter(4, 4))) } { \refused }
    // refused
    write { |element|
        if (element.isKindOf(MusicScore).not) {
            Error("ScoreJSONWriter: a % is not a document - the format and "
                "version envelope belongs to the score. Wrap it in a MusicScore "
                "before writing.".format(element.class)).throw
        };
        ^super.write(element)
    }

    // A JSON string may not contain a raw control character, not a newline,
    // not a tab, nothing below U+0020, so every one of them is spelled as an
    // escape. A strict reader rejects the document otherwise, and a title with
    // a line break in it is enough to produce one.
    //
    // The six that JSON names get their short spelling and the rest go as
    // \uXXXX. Backslash is handled first, as ever: doing it later would escape
    // the escapes just added.
    //
    // Bytes above 127 are passed through. sclang strings are UTF-8 bytes, whose
    // `ascii` is negative here, and JSON takes UTF-8 directly, so escaping
    // them would be work that only risked mangling them.
    //
    // >>> ScoreJSONWriter.escape("a\"b").asCompileString   -> "a\\\"b"
    *escape { |string|
        var digits = "0123456789abcdef";
        var out = String.new;
        string.asString.do { |char|
            var code = char.ascii;
            case
                { char == $\\ } { out = out ++ "\\\\" }
                { char == $\" } { out = out ++ "\\\"" }
                { char == $\n } { out = out ++ "\\n" }
                { char == $\r } { out = out ++ "\\r" }
                { char == $\t } { out = out ++ "\\t" }
                { code == 8 }   { out = out ++ "\\b" }
                { code == 12 }  { out = out ++ "\\f" }
                { code >= 0 and: { code < 32 } } {
                    out = out ++ "\\u00"
                        ++ digits[code div: 16].asString
                        ++ digits[code % 16].asString
                }
                { true } { out = out.add(char) };
        };
        ^out
    }

    writePair { |dur| stream << "[" << dur.numerator << "," << dur.denominator << "]" }

    writeString { |key, value|
        if (value.isNil) { ^this };
        stream << ",\"" << key << "\":\"" << ScoreJSONWriter.escape(value) << "\"";
        ^this
    }

    writeElements { |container, key = "elements"|
        stream << ",\"" << key << "\":[";
        container.children.do { |c, i|
            if (i > 0) { stream << "," };
            c.accept(this);
        };
        stream << "]";
        ^this
    }

    visitScore { |score|
        stream << "{\"format\":\"" << ScoreJSONWriter.schemaFormat
               << "\",\"version\":" << ScoreJSONWriter.schemaVersion
               << ",\"type\":\"score\"";
        this.writeString("title", score.title);
        this.writeString("composer", score.composer);
        this.writeElements(score, "staves");
        stream << "}";
    }

    visitStaff { |staff|
        stream << "{\"type\":\"staff\"";
        this.writeString("name", staff.name);
        this.writeString("clef", staff.clef);
        this.writeElements(staff, "measures");
        stream << "}";
    }

    // Written only when the bar is short, so a full bar is the shape it always
    // was.
    visitMeasure { |measure|
        stream << "{\"type\":\"measure\",\"meter\":["
               << measure.meter.count << "," << measure.meter.unit << "]";
        if (measure.meter.isGrouped) {
            stream << ",\"meterGrouping\":[" << measure.meter.groups.join(",") << "]"
        };
        // Written only when this bar changes it.
        measure.clef !? { |clef| stream << ",\"clef\":\"" << clef << "\"" };
        this.writeDirections(measure);
        if (measure.isPartial) {
            stream << ",\"barDuration\":";
            this.writePair(measure.barDuration);
            stream << ",\"metricOffset\":";
            this.writePair(measure.metricOffset);
        };
        this.writeElements(measure);
        stream << "}";
    }

    // The counts travel only when they say something the multiplier does not.
    // A 3:2 reduces to itself, so an ordinary triplet writes none.
    visitTuplet { |tuplet|
        stream << "{\"type\":\"tuplet\",\"multiplier\":";
        this.writePair(tuplet.multiplier);
        if (tuplet.countsAreReduced.not) {
            stream << ",\"actualNotes\":" << tuplet.actualNotes
                   << ",\"normalNotes\":" << tuplet.normalNotes
        };
        this.writeElements(tuplet);
        stream << "}";
    }

    visitContainer { |container|
        stream << "{\"type\":\"container\"";
        this.writeElements(container);
        stream << "}";
    }

    // A bar with voices carries them as explicit nodes. One without holds its
    // elements directly.
    visitVoice { |voice|
        stream << "{\"type\":\"voice\"";
        this.writeString("name", voice.name);
        this.writeElements(voice);
        stream << "}";
    }

    // Tie data is written only when there is any. An absent field reads as
    // "does not tie".
    visitNote { |note|
        stream << "{\"type\":\"note\",\"pitch\":";
        this.writePitch(note.pitch);
        stream << ",\"duration\":";
        this.writePair(note.dur);
        if (note.tiesToNext) { stream << ",\"tiesToNext\":true" };
        this.writeMarkings(note);
        this.writeSpanners(note);
        this.writeGraces(note);
        stream << "}";
    }

    visitRest { |rest|
        stream << "{\"type\":\"rest\",\"duration\":";
        this.writePair(rest.dur);
        this.writeMarkings(rest);
        this.writeSpanners(rest);
        this.writeGraces(rest);
        stream << "}";
    }

    // A chord always writes the whole mask when anything ties, so a partial tie
    // survives exactly, and a whole tie is an all-true mask rather than a
    // second spelling of the same fact.
    visitChord { |chord|
        stream << "{\"type\":\"chord\",\"pitches\":[";
        chord.pitches.do { |p, i|
            if (i > 0) { stream << "," };
            this.writePitch(p);
        };
        stream << "],\"duration\":";
        this.writePair(chord.dur);
        if (chord.tiesAnything) {
            stream << ",\"tiesToNext\":[";
            chord.tiesToNext.do { |flag, i|
                if (i > 0) { stream << "," };
                stream << flag;
            };
            stream << "]";
        };
        this.writeMarkings(chord);
        this.writeSpanners(chord);
        this.writeGraces(chord);
        stream << "}";
    }

    // Written only when there are any, and in the order they were attached: a
    // tempo before a rehearsal mark is not the same page as the reverse.
    writeDirections { |measure|
        if (measure.directions.isEmpty) { ^this };
        stream << ",\"directions\":[";
        measure.directions.do { |direction, i|
            if (i > 0) { stream << "," };
            stream << "{\"type\":\"" << direction.kind << "\"";
            // Absent only on a metronome mark with no words, which is the one
            // direction that says something without prose.
            direction.text !? { |text|
                stream << ",\"text\":\"" << ScoreJSONWriter.escape(text) << "\""
            };
            // Omitted at zero, which is where a direction stands unless it says
            // otherwise.
            if (direction.atBarStart.not) {
                stream << ",\"offset\":";
                this.writePair(direction.offset);
            };
            // Both or neither, in the order they are said: `4 = 132`.
            if (direction.hasMetronome) {
                stream << ",\"unit\":";
                this.writePair(direction.unit);
                stream << ",\"perMinute\":" << direction.perMinute;
            };
            stream << "}";
        };
        stream << "]";
        ^this
    }

    // Written only when there are any, and in attachment order. A dynamic
    // before an articulation is not the same instruction as the reverse.
    writeMarkings { |leaf|
        if (leaf.markings.isEmpty) { ^this };
        stream << ",\"markings\":[";
        leaf.markings.do { |marking, i|
            if (i > 0) { stream << "," };
            // The value is escaped for every kind, though only text can contain
            // anything needing it: a vocabulary word that suddenly did would be
            // a bug worth writing correctly rather than corrupting the
            // document.
            stream << "{\"type\":\"" << marking.kind
                   << "\",\"value\":\"" << ScoreJSONWriter.escape(marking.value)
                   << "\"";
            if (marking.placement.notNil) {
                stream << ",\"placement\":\"" << marking.placement << "\""
            };
            stream << "}";
        };
        stream << "]";
        ^this
    }

    writeSpanners { |leaf|
        if (leaf.spanners.isEmpty) { ^this };
        stream << ",\"spanners\":[";
        leaf.spanners.do { |endpoint, i|
            if (i > 0) { stream << "," };
            stream << "{\"type\":\"" << endpoint.kind
                   << "\",\"edge\":\"" << endpoint.edge
                   << "\",\"id\":" << endpoint.id;
            if (endpoint.direction.notNil) {
                stream << ",\"direction\":\"" << endpoint.direction << "\""
            };
            if (endpoint.text.notNil) {
                stream << ",\"text\":\"" << ScoreJSONWriter.escape(endpoint.text)
                       << "\",\"placement\":\"" << endpoint.placement << "\""
            };
            stream << "}";
        };
        stream << "]";
        ^this
    }

    // Version 18. The group before a leaf, and the style only when it is not
    // the plain one, so a document without ornaments is the one v17 wrote.
    //
    // A grace leaf is tagged apart from a note because its duration is a note
    // head and not a length. Reusing "note" would cost both decoders nothing
    // and would let a consumer walking every "note" sum display values into the
    // bar, which is safe today only because a note sits in one place.
    writeGraces { |leaf|
        if (leaf.hasGraces.not) { ^this };
        // Refused here as well as in the Validator, because a raw writer runs
        // no validation and this method writes a pitch and a duration only. A
        // grace leaf carrying anything else would be written without it, which
        // is the silent drop the schema exists to prevent. See the Correctness
        // layer: a refusal a writer depends on is stated in the writer too.
        leaf.graces.do { |grace|
            var tied;
            // What it is, before anything is asked of it. `graces_` takes what
            // it is given, so a group can hold an object that answers none of
            // the questions below, and asking first turns a stated refusal into
            // a missing-method error naming a selector rather than the problem.
            if (grace.isKindOf(MusicNote).not and: { grace.isKindOf(Chord).not }) {
                Error("ScoreJSONWriter: a grace group holds a %, and the format "
                    "says notes and chords.".format(grace.class)).throw
            };
            tied = if (grace.isKindOf(Chord)) {
                grace.tiedPitches.notEmpty
            } {
                grace.tiesToNext
            };
            if (tied or: { grace.hasMarkings } or: { grace.hasSpanners }
                or: { grace.hasGraces }) {
                Error("ScoreJSONWriter: a grace leaf carries a tie, a marking, a "
                    "spanner or a group of its own, and this format says a grace "
                    "leaf's pitch and duration. Writing it would drop what the "
                    "tree holds.").throw
            };
            // Stricter than an ordinary duration, and deliberately. A 5/8 note
            // is admitted unprepared because preparation can cut it into tied
            // note heads. Nothing ever re-cuts a grace group, so a display
            // value that is no note head has no repair anywhere and would
            // reach a backend neither could draw.
            if (grace.dur.isNotatable.not) {
                Error("ScoreJSONWriter: a grace note's % is a display value and "
                    "has to be one note head. Nothing splits a grace group, so "
                    "there is no pass that could make this one."
                    .format(grace.dur)).throw
            };
        };
        if (leaf.graceStyle != \grace) {
            stream << ",\"graceStyle\":\"" << leaf.graceStyle << "\""
        };
        stream << ",\"graces\":[";
        leaf.graces.do { |grace, i|
            if (i > 0) { stream << "," };
            if (grace.isKindOf(Chord)) {
                stream << "{\"type\":\"graceChord\",\"pitches\":[";
                grace.pitches.do { |pitch, j|
                    if (j > 0) { stream << "," };
                    this.writePitch(pitch);
                };
                stream << "]";
            } {
                stream << "{\"type\":\"graceNote\",\"pitch\":";
                this.writePitch(grace.pitch);
            };
            stream << ",\"duration\":";
            this.writePair(grace.dur);
            stream << "}";
        };
        stream << "]";
        ^this
    }

    writePitch { |pitch|
        stream << "{\"step\":" << pitch.step << ",\"alter\":";
        this.writePair(pitch.alter);
        stream << ",\"octave\":" << pitch.octave << ",\"cents\":" << pitch.cents << "}";
    }
}


// Rebuild a tree from the interchange format.
//
// sclang's String:parseJSON returns every scalar as a String, so every number
// arrives as text and has to be parsed here, strictly, by `integerValue` and
// `positiveInteger`, which say why. The envelope's version check and the
// Boolean and mask readers are strict for the same reason: at an interchange
// boundary, coercion is how a wrong document becomes a plausible tree.
ScoreJSONReader {

    // A document is checked at the envelope before any of it is believed. The
    // point is that a consumer, or a future Rastrum, gets one clear error
    // rather than a confusing failure somewhere deep in the tree, or worse, a
    // tree that parses under the wrong assumptions.
    //
    // Only a whole score carries the envelope, so a fragment written from a
    // bare Measure or Tuplet is not a document and is refused here.
    *read { |string|
        var dict = string.parseJSON;
        this.checkEnvelope(dict);
        ^this.element(dict)
    }

    *checkEnvelope { |dict|
        var format, version;
        if (dict.isKindOf(Dictionary).not) {
            Error("ScoreJSONReader: expected a JSON object, got %".format(
                dict.class)).throw
        };
        format = dict["format"];
        version = dict["version"];
        if (format.isNil) {
            Error("ScoreJSONReader: no \"format\" key. Only a whole score carries "
                "the envelope; a fragment written from a bare Measure or Tuplet "
                "is not a document.").throw
        };
        if (format.asString != ScoreJSONWriter.schemaFormat) {
            Error("ScoreJSONReader: format is \"%\", expected \"%\"".format(
                format, ScoreJSONWriter.schemaFormat)).throw
        };
        if (version.isNil) {
            Error("ScoreJSONReader: no \"version\" key in a % document".format(
                ScoreJSONWriter.schemaFormat)).throw
        };
        // Exact string equality on purpose, for the reason `integerValue`
        // gives.
        if (version.asString != ScoreJSONWriter.schemaVersion.asString) {
            Error("ScoreJSONReader: this reader understands % version %, but the "
                "document declares version %".format(
                    ScoreJSONWriter.schemaFormat, ScoreJSONWriter.schemaVersion,
                    version)).throw
        };
        ^dict
    }

    // What each node must say, and what it may. `[type, required, optional]`.
    //
    // A key outside its row was read by nothing, and dropping what a document
    // says is worse than refusing it. The failures are quiet ones: a misspelled
    // "tiesToNex" loses a tie, a misspelled "staves" hands back an empty score
    // that validates, and a key from a version this reader does not speak reads
    // as a leaf missing whatever it said.
    //
    // Strictness costs nothing here because the envelope pins one version, so a
    // document that reaches this is fully specified by this table. That is also
    // why widening it is a version rather than a fix.
    //
    // "format" and "version" ride on the score node and belong to the envelope,
    // which has already checked them.
    //
    // One table rather than a test in each arm, so a node type added later is
    // covered without anyone having to remember this.
    *nodeKeys {
        ^[
            ["score", ["staves"], ["format", "version", "title", "composer"]],
            ["staff", ["measures"], ["name", "clef"]],
            ["measure", ["meter", "elements"],
                ["meterGrouping", "clef", "directions", "barDuration",
                 "metricOffset"]],
            ["tuplet", ["multiplier", "elements"], ["actualNotes", "normalNotes"]],
            ["voice", ["elements"], ["name"]],
            ["container", ["elements"], []],
            ["note", ["pitch", "duration"],
                ["tiesToNext", "markings", "spanners", "graces", "graceStyle"]],
            ["rest", ["duration"],
                ["markings", "spanners", "graces", "graceStyle"]],
            ["chord", ["pitches", "duration"],
                ["tiesToNext", "markings", "spanners", "graces", "graceStyle"]]
        ]
    }

    // Every node type this key may sit on, so a misplaced one is told where it
    // belongs rather than only that it is wrong. "directions" on a note is a
    // likelier mistake than "directions" being unknown.
    *keyHomes { |key|
        ^this.nodeKeys
            .select { |row| (row[1] ++ row[2]).any { |each| each == key } }
            .collect { |row| row[0] }
    }

    // The same rule for the records hanging off a node. `nodeKeys` covers the
    // tree and these cover what sits inside it, so an unknown field is refused
    // wherever it is written rather than only at the top: a pitch carrying
    // "temperament" used to read, validate, and vanish on the way out.
    //
    // These record what the readers already require. `cents` is optional
    // because `floatValue` answers 0 for a missing one, where `integerValue`
    // throws, so widening or narrowing either would be a version rather than a
    // tidy-up.
    *recordKeys {
        ^[
            ["pitch", ["step", "alter", "octave"], ["cents"]],
            ["marking", ["type", "value"], ["placement"]],
            ["spanner", ["type", "edge", "id"], ["direction", "text", "placement"]],
            ["direction", ["type"], ["text", "offset", "unit", "perMinute"]],
            ["graceNote", ["type", "pitch", "duration"], []],
            ["graceChord", ["type", "pitches", "duration"], []]
        ]
    }

    // `any { == }` rather than `includes`, because Array:includes compares by
    // identity: ["measure"].includes("measure") is false for two Strings that
    // spell the same word. Everywhere else in this quark a vocabulary is
    // Symbols, which are interned and so safe under identity. The wire hands
    // back Strings.
    *checkKeys { |dict, kind|
        var row;
        if (kind.isKindOf(String).not) { ^this };   // the switch names it better
        row = this.nodeKeys.detect { |each| each[0] == kind };
        if (row.isNil) { ^this };                   // and names an unknown type
        ^this.prCheckKeys(dict, "a \"" ++ kind ++ "\" node",
            ["type"] ++ row[1] ++ row[2], row[1], true)
    }

    *checkRecord { |dict, name|
        var row = this.recordKeys.detect { |each| each[0] == name };
        if (dict.isKindOf(Dictionary).not) { ^this };  // said better where read
        ^this.prCheckKeys(dict, "a " ++ name, row[1] ++ row[2], row[1], false)
    }

    // `named` says whether an unknown key is worth locating. A node key has one
    // or more homes in the tree and saying which is more use than saying no,
    // where a record's key names nothing a reader could look up.
    *prCheckKeys { |dict, what, allowed, required, named|
        required.do { |key|
            if (dict[key].isNil) {
                Error("ScoreJSONReader: % says no \"%\". Every one of % is "
                    "required, and a reader filling one in would invent music "
                    "the document does not contain.".format(
                        what, key, required)).throw
            }
        };
        dict.keys.do { |key|
            var homes = if (named) { this.keyHomes(key) } { [] };
            if (allowed.any { |each| each == key }.not) {
                Error(if (homes.isEmpty) {
                    "ScoreJSONReader: \"%\" is no key of this schema, and a "
                    "reader passing over it would drop what the document says. "
                    "% says %.".format(key, what, allowed)
                } {
                    "ScoreJSONReader: \"%\" on %. It belongs on %, and a reader "
                    "passing over it here would drop what the document says."
                        .format(key, what, homes)
                }).throw
            }
        };
        ^this
    }

    *element { |dict|
        var kind = dict["type"];
        this.checkKeys(dict, kind);
        ^switch(kind,
            "score", {
                MusicScore(
                    dict["staves"].collect { |d| this.element(d) },
                    dict["title"], dict["composer"])
            },
            "staff", {
                Staff(
                    dict["measures"].collect { |d| this.element(d) },
                    dict["name"], dict["clef"] !? { |c| c.asSymbol })
            },
            "measure", {
                // Both fields or neither: a bar that says how long it is must
                // also say where that span sits, because the two together are
                // what distinguish a pickup from a final short bar.
                if (dict["barDuration"].isNil != dict["metricOffset"].isNil) {
                    Error("ScoreJSONReader: a partial measure needs both "
                        "\"barDuration\" and \"metricOffset\"; one without the "
                        "other says how long the bar is without saying where it "
                        "sits, or the reverse.").throw
                };
                // `clef_` holds it to the same closed vocabulary sclang does,
                // so a document cannot name a clef no writer can spell.
                this.withDirections(Measure.partial(
                    this.meter(dict),
                    dict["elements"].collect { |d| this.element(d) },
                    dict["barDuration"] !? { |p| this.duration(p) },
                    dict["metricOffset"] !? { |p| this.duration(p) })
                    .clef_(dict["clef"]),
                    dict["directions"])
            },
            "tuplet", {
                this.tuplet(dict, dict["elements"].collect { |d| this.element(d) })
            },
            "container", {
                ScoreContainer(dict["elements"].collect { |d| this.element(d) })
            },
            "voice", {
                Voice(dict["elements"].collect { |d| this.element(d) }, dict["name"])
            },
            "note", {
                this.decorate(
                    MusicNote(this.pitch(dict["pitch"]), this.duration(dict["duration"]),
                        this.boolean(dict["tiesToNext"], "a note's tiesToNext")),
                    dict)
            },
            "rest", {
                this.decorate(MusicRest(this.duration(dict["duration"])), dict)
            },
            "chord", {
                this.decorate(
                    Chord(
                        dict["pitches"].collect { |d| this.pitch(d) },
                        this.duration(dict["duration"]),
                        this.tieMask(dict["tiesToNext"])),
                    dict)
            },
            { Error("ScoreJSONReader: unknown element type %".format(kind)).throw }
        )
    }

    // A meter is a pair, and optionally the grouping the pair cannot carry.
    //
    // The grouping is rebuilt through `Meter.grouped`, so what a grouping may
    // be is decided in the model rather than restated here and drifting from
    // it. What is checked here is what only the wire knows: that it arrived as
    // a list of numbers rather than as a string or an object.
    *meter { |dict|
        var raw = dict["meterGrouping"];
        var count = this.positiveInteger(dict["meter"][0], "a meter count");
        var unit = this.positiveInteger(dict["meter"][1], "a meter unit");
        if (raw.isNil) { ^Meter(count, unit) };
        if (raw.isKindOf(String) or: { raw.isSequenceableCollection.not }) {
            Error("ScoreJSONReader: \"meterGrouping\" must be a list of whole "
                "units, got %".format(raw)).throw
        };
        ^Meter.grouped(count, unit,
            raw.collect { |value| this.integerValue(value, "a meter group") })
    }

    *duration { |pair|
        if (pair.isKindOf(String) or: { pair.isSequenceableCollection.not }
            or: { pair.size != 2 }) {
            Error("ScoreJSONReader: a duration must be [numerator, denominator], "
                "got %".format(pair)).throw
        };
        ^Duration(
            this.integerValue(pair[0], "a duration numerator"),
            this.positiveInteger(pair[1], "a duration denominator"))
    }

    // Strict integer parsing: `asInteger` reads "1abc", "1.5" and "01" all as
    // 1, so a malformed document would be accepted as a well-formed one.
    // Comparing against the canonical rendering of what was parsed is what
    // rejects them, and this is the one place that knows a number arrived as
    // text.
    *integerValue { |value, what|
        var text, parsed;
        if (value.isNil) {
            Error("ScoreJSONReader: % is missing".format(what)).throw
        };
        text = value.asString;
        parsed = text.asInteger;
        if (parsed.asString != text) {
            Error("ScoreJSONReader: % must be a whole number, got \"%\"".format(
                what, text)).throw
        };
        ^parsed
    }

    // `cents` is the one field that is genuinely fractional, so it cannot use
    // the canonical-rendering trick. This writer emits "0.0" throughout,
    // `MusicPitch` holding cents as a Float however a pitch was built, but a
    // document is not only ever written here: "0" is the same number and a
    // producer that writes it is not wrong. So the lexical form is checked
    // instead: an optional sign, digits, at most one point, which rejects
    // "0abc", "0.0abc" and "1.5x" while accepting either spelling of zero.
    //
    // Exponent notation is refused. Cents are a deviation of at most a semitone
    // or two, so plain decimal covers every value the field can hold, and
    // admitting `1e2` would widen the parser for nothing. An absent field means
    // no deviation.
    *floatValue { |value, what|
        var text, seenDigit = false, seenPoint = false, index = 0;
        if (value.isNil) { ^0 };
        text = value.asString;
        if (text.isEmpty) {
            Error("ScoreJSONReader: % is empty".format(what)).throw
        };
        if ((text[0] == $-) or: { text[0] == $+ }) { index = 1 };
        while { index < text.size } {
            var char = text[index];
            case
                { char.isDecDigit } { seenDigit = true }
                { (char == $.) and: { seenPoint.not } } { seenPoint = true }
                { true } {
                    Error("ScoreJSONReader: % must be a plain decimal number, got "
                        "\"%\"".format(what, text)).throw
                };
            index = index + 1;
        };
        if (seenDigit.not) {
            Error("ScoreJSONReader: % has no digits, got \"%\"".format(
                what, text)).throw
        };
        ^text.asFloat
    }

    *positiveInteger { |value, what|
        var parsed = this.integerValue(value, what);
        if (parsed < 1) {
            Error("ScoreJSONReader: % must be greater than zero, got %".format(
                what, parsed)).throw
        };
        ^parsed
    }

    // Markings are rebuilt through Marking's own factories, so the wire cannot
    // carry a kind or a value the model would refuse. One vocabulary, checked
    // in one place, whether a marking was attached in sclang or arrived as
    // JSON. Everything a leaf can carry beside its pitch and duration, in one
    // place so a new kind is added to three arms at once rather than to one of
    // them.
    *decorate { |leaf, dict|
        this.withMarkings(leaf, dict["markings"]);
        this.withSpanners(leaf, dict["spanners"]);
        this.withGraces(leaf, dict["graces"], dict["graceStyle"]);
        ^leaf
    }

    // Version 18. A grace leaf is read by its own tag rather than through
    // `element`, so a "graceNote" cannot appear where an element belongs and an
    // element cannot appear in a group. `graces_` holds the style to the same
    // two words the model does.
    *withGraces { |leaf, list, style|
        if (list.isNil) {
            if (style.notNil) {
                Error("ScoreJSONReader: \"graceStyle\" with no \"graces\". A "
                    "style says how a group is written, and there is no "
                    "group.").throw
            };
            ^leaf
        };
        if (list.isKindOf(String) or: { list.isSequenceableCollection.not }) {
            Error("ScoreJSONReader: \"graces\" must be a list, got %".format(
                list)).throw
        };
        if (list.isEmpty) {
            Error("ScoreJSONReader: \"graces\" is empty. A leaf with no "
                "ornament writes no field at all, so an empty group is a "
                "document saying something it does not mean.").throw
        };
        ^leaf.graces_(list.collect { |entry| this.graceLeaf(entry) },
            (style ? "grace").asSymbol)
    }

    // Stricter than `duration`, and at the schema level rather than the
    // Validator's, because the wire says a grace's duration *is* a note head.
    // An ordinary duration is admitted unnotatable, preparation being able to
    // cut it into tied heads. Nothing re-cuts a grace group, so there is no
    // pass anywhere that could make this one, and a document saying otherwise
    // means something no reader can honour.
    *graceDuration { |pair|
        var dur = this.duration(pair);
        if (dur.isNotatable.not) {
            Error("ScoreJSONReader: a grace's duration is %, which no single "
                "note head carries. Nothing re-cuts a grace group, so there is "
                "no pass that could spell it.".format(pair)).throw
        };
        ^dur
    }

    *graceLeaf { |dict|
        if (dict.isKindOf(Dictionary).not) {
            Error("ScoreJSONReader: a grace must be an object with a \"type\", "
                "got %".format(dict)).throw
        };
        this.checkRecord(dict, if (dict["type"] == "graceChord") {
            "graceChord"
        } {
            "graceNote"
        });
        ^switch(dict["type"],
            "graceNote", {
                MusicNote(this.pitch(dict["pitch"]),
                    this.graceDuration(dict["duration"]))
            },
            "graceChord", {
                Chord(dict["pitches"].collect { |each| this.pitch(each) },
                    this.graceDuration(dict["duration"]))
            },
            {
                Error("ScoreJSONReader: a grace group holds a \"%\". It holds "
                    "\"graceNote\" and \"graceChord\", whose durations are note "
                    "heads rather than lengths.".format(dict["type"])).throw
            })
    }

    *withSpanners { |leaf, list|
        if (list.isNil) { ^leaf };
        if (list.isKindOf(String) or: { list.isSequenceableCollection.not }) {
            Error("ScoreJSONReader: \"spanners\" must be a list, got %".format(
                list)).throw
        };
        list.do { |entry|
            if (entry.isKindOf(Dictionary).not) {
                Error("ScoreJSONReader: a spanner must be an object with \"type\", "
                    "\"edge\" and \"id\", got %".format(entry)).throw
            };
            this.checkRecord(entry, "spanner");
            if (entry["type"].isNil or: { entry["edge"].isNil }
                or: { entry["id"].isNil }) {
                Error("ScoreJSONReader: a spanner needs \"type\", \"edge\" and "
                    "\"id\"; got %".format(entry)).throw
            };
            // Direction, text and placement are handed to Spanner as they
            // arrived, nil included, so the one rule about where each is
            // meaningful lives in the model rather than being restated here and
            // drifting from it. What is checked here is what only the wire
            // knows: that text arrived as a string rather than as a number or a
            // list.
            if (entry["text"].notNil and: { entry["text"].isKindOf(String).not }) {
                Error("ScoreJSONReader: a spanner's text must be a string, got "
                    "%".format(entry["text"])).throw
            };
            leaf.attach(Spanner.of(entry["type"].asSymbol, entry["edge"].asSymbol,
                this.positiveInteger(entry["id"], "a spanner id"),
                entry["direction"] !? { |d| d.asSymbol },
                entry["text"],
                entry["placement"] !? { |p| p.asSymbol }));
        };
        ^leaf
    }

    *withMarkings { |leaf, list|
        if (list.isNil) { ^leaf };
        if (list.isKindOf(String) or: { list.isSequenceableCollection.not }) {
            Error("ScoreJSONReader: \"markings\" must be a list, got %".format(
                list)).throw
        };
        list.do { |entry|
            if (entry.isKindOf(Dictionary).not) {
                Error("ScoreJSONReader: a marking must be an object with \"type\" "
                    "and \"value\", got %".format(entry)).throw
            };
            if (entry["type"].isNil or: { entry["value"].isNil }) {
                Error("ScoreJSONReader: a marking needs both \"type\" and "
                    "\"value\"; got %".format(entry)).throw
            };
            this.checkRecord(entry, "marking");
            leaf.attach(this.marking(entry));
        };
        ^leaf
    }

    // Directions belong to the bar, so they are attached to it rather than
    // passed through a constructor. Each is handed to `Direction` as it
    // arrived, so the one rule about what a kind and a text may be lives in the
    // model rather than being restated here and drifting from it.
    //
    // The division of labor: this checks the *shapes* the wire can get wrong,
    // a text that is a number, a count that is not a whole number, half a
    // metronome mark, and `Direction` decides what is musically legal. A
    // metronome on a rehearsal mark, or a tempo saying nothing at all, is
    // refused there and so is refused identically however the direction was
    // built.
    *withDirections { |measure, list|
        if (list.isNil) { ^measure };
        if (list.isKindOf(String) or: { list.isSequenceableCollection.not }) {
            Error("ScoreJSONReader: \"directions\" must be a list, got %".format(
                list)).throw
        };
        list.do { |entry|
            if (entry.isKindOf(Dictionary).not) {
                Error("ScoreJSONReader: a direction must be an object with a "
                    "\"type\" and something to say, got %".format(entry)).throw
            };
            this.checkRecord(entry, "direction");
            if (entry["type"].isNil) {
                Error("ScoreJSONReader: a direction needs a \"type\"; got %"
                    .format(entry)).throw
            };
            // Optional since version 16, and only where a metronome mark stands
            // in for it, "unit" and "perMinute" are what make a wordless tempo
            // legible, and `Direction` refuses the pair that says neither.
            if (entry["text"].isNil and: { entry["unit"].isNil }) {
                Error("ScoreJSONReader: a direction needs \"text\", or a "
                    "metronome mark to say what it says instead; got %".format(
                        entry)).throw
            };
            if (entry["text"].notNil and: { entry["text"].isKindOf(String).not }) {
                Error("ScoreJSONReader: a direction's text must be a string, got "
                    "%".format(entry["text"])).throw
            };
            if (entry["unit"].isNil != entry["perMinute"].isNil) {
                Error("ScoreJSONReader: a metronome mark needs both \"unit\" and "
                    "\"perMinute\"; one without the other is half a mark. Got %"
                    .format(entry)).throw
            };
            measure.attach(Direction.of(entry["type"].asSymbol, entry["text"],
                entry["offset"] !? { |pair| this.duration(pair) } ? 0,
                entry["unit"] !? { |pair| this.duration(pair) },
                entry["perMinute"] !? { |value|
                    this.positiveInteger(value, "a metronome mark's count")
                }));
        };
        ^measure
    }

    // Returns one Tuplet, with the counts its bracket was authored with if it
    // carries any.
    //
    // Both or neither: a count without its partner describes half a bracket.
    // The pair must also reduce to the multiplier beside it. Reading either
    // fact and ignoring the other would pick a winner silently. See
    // Note [A bracket is two facts] in MusicScore.sc.
    *tuplet { |dict, elements|
        var multiplier = this.duration(dict["multiplier"]);
        var actual = dict["actualNotes"];
        var normal = dict["normalNotes"];
        if (actual.isNil and: { normal.isNil }) { ^Tuplet(multiplier, elements) };
        if (actual.isNil or: { normal.isNil }) {
            Error("ScoreJSONReader: a tuplet needs both \"actualNotes\" and "
                "\"normalNotes\" or neither; one without the other is half a "
                "bracket.").throw
        };
        actual = this.positiveInteger(actual, "a tuplet's actual notes");
        normal = this.positiveInteger(normal, "a tuplet's normal notes");
        if (Duration(normal, actual) != multiplier) {
            Error("ScoreJSONReader: a tuplet of %:% does not scale time by %. The "
                "counts and the multiplier describe the same bracket, so they "
                "cannot disagree.".format(actual, normal, multiplier)).throw
        };
        ^Tuplet.ratio(actual, normal, elements)
    }

    // Returns one Marking from its object.
    //
    // Text keeps its value as the String it arrived as. Every other kind is a
    // vocabulary word and becomes a Symbol, which is also what refuses a text
    // value on a dynamic. `placement` belongs to text alone: it is required
    // there, and refused everywhere else rather than ignored.
    //
    // Required, not defaulted, even though `Marking.text` itself defaults to
    // above. A constructor default is a convenience for someone writing music.
    // a document is a record of what a writer decided, and filling in the blank
    // would read a half-written document as a whole one.
    *marking { |entry|
        var kind = entry["type"].asSymbol;
        var placement = entry["placement"];
        if (kind != \text) {
            if (placement.notNil) {
                Error("ScoreJSONReader: a % marking carries a \"placement\", which "
                    "only text has. Nothing would read it.".format(kind)).throw
            };
            ^Marking.of(kind, entry["value"].asSymbol)
        };
        if (entry["value"].isKindOf(String).not) {
            Error("ScoreJSONReader: text must be a string, got %".format(
                entry["value"])).throw
        };
        if (placement.isNil) {
            Error("ScoreJSONReader: a text marking needs a \"placement\" of "
                "above or below. Which side of the staff it sits on is part of "
                "what it says, so there is no default to fall back on.").throw
        };
        ^Marking.text(entry["value"], placement.asSymbol)
    }

    // String:parseJSON hands back every scalar as a String, so a JSON `true`
    // arrives as "true". Match the two spellings exactly and reject everything
    // else: `asBoolean`-style coercion would read "yes", "1" or a typo as false
    // and quietly drop a tie.
    //
    // An absent field means "does not tie". A JSON null reads the same way, and
    // has to: parseJSON maps both an absent key and an explicit null to nil, so
    // the two cannot be told apart here.
    *boolean { |value, what|
        if (value.isNil) { ^false };
        if (value.isKindOf(Boolean)) { ^value };
        if (value == "true") { ^true };
        if (value == "false") { ^false };
        Error("ScoreJSONReader: % must be true or false, got %".format(
            what, value)).throw
    }

    // A chord's tie field is absent, or one flag per pitch, never a bare
    // Boolean. A whole chord tie is an all-true mask, so accepting `true` here
    // would give the same fact two spellings on the wire, and an interchange
    // contract that admits two spellings gets read as one of them by somebody.
    //
    // Note that a String is a SequenceableCollection in sclang, so it has to be
    // excluded before this treats a value as a mask, otherwise "true" would
    // decode as four flags. Length and all-false masks are left to Chord's own
    // validation, so the wire and the model cannot disagree about what is
    // valid.
    *tieMask { |value|
        if (value.isNil) { ^false };
        if (value.isKindOf(String).not and: { value.isSequenceableCollection }) {
            ^value.collect { |flag| this.boolean(flag, "a chord tie flag") }
        };
        Error("ScoreJSONReader: a chord's tiesToNext must be one flag per pitch, "
            "got %. A whole chord tie is an all-true mask, not true.".format(
                value)).throw
    }

    *pitch { |dict|
        this.checkRecord(dict, "pitch");
        ^MusicPitch(
            this.integerValue(dict["step"], "a pitch step"),
            this.duration(dict["alter"]),
            this.integerValue(dict["octave"], "a pitch octave"),
            this.floatValue(dict["cents"], "a pitch's cents"))
    }
}
