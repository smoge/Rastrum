// ScoreJSONReader: rebuilds a tree from the interchange format.
//
// The envelope and schema constants live in ScoreJSONContract, so reader and
// writer version bumps touch one class.
//
// sclang's `String:parseJSON` returns scalars as Strings. Parse numeric and
// Boolean fields strictly here.
ScoreJSONReader {

    // Check the envelope before trusting the document.
    *read { |string|
        var dict = string.parseJSON;
        this.checkEnvelope(dict);
        ^this.element(dict)
    }

    *checkEnvelope { |dict|
        var format, version;
        if (dict.isKindOf(Dictionary).not) {
            Error("ScoreJSONReader: expected a JSON object, got %".format(dict.class)).throw
        };
        format = dict["format"];
        version = dict["version"];
        if (format.isNil) {
            Error("ScoreJSONReader: missing \"format\" key in document envelope.").throw
        };
        if (format.asString != ScoreJSONContract.schemaFormat) {
            Error("ScoreJSONReader: format is \"%\", expected \"%\"".format(
                format, ScoreJSONContract.schemaFormat)).throw
        };
        if (version.isNil) {
            Error("ScoreJSONReader: no \"version\" key in a % document".format(
                ScoreJSONContract.schemaFormat)).throw
        };
        // Exact string equality, as for integer fields.
        if (version.asString != ScoreJSONContract.schemaVersion.asString) {
            Error("ScoreJSONReader: this reader understands % version %, but the "
                "document declares version %".format(
                    ScoreJSONContract.schemaFormat, ScoreJSONContract.schemaVersion,version)).throw
        };
        ^dict
    }

    // What each node must say, and what it may: `[type, required, optional]`.
    //
    // A key outside its row would be ignored; refusing preserves the
    // document. The envelope pins one version; this table specifies
    // that version. "format" and "version" ride on the score node and
    // belong to the envelope, which has already checked them.
    //
    // One table, not one test per arm.
    //
    // >>> ScoreJSONReader.nodeKeys.detect { |row| row[0] == "score" }[1]
    // [ staves ]
    *nodeKeys {
        ^[
            ["score", ["staves"], ["format", "version", "title", "composer"]],
            ["staff", ["measures"], ["name", "shortName", "clef"]],
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

    // Every node type this key may sit on, for misplaced-key messages.
    *keyHomes { |key|
        ^this.nodeKeys
            .select { |row| (row[1] ++ row[2]).any { |each| each == key } }
            .collect { |row| row[0] }
    }

    // The same rule for records hanging off a node. Unknown fields
    // are refused where they are written, not only at the top.
    //
    // These record what the readers require. Widening or narrowing
    // the table is a version change.
    //
    // >>> ScoreJSONReader.recordKeys.detect { |row| row[0] == "direction" }[2]
    //     .any { |key| key == "edge" }
    // true
    *recordKeys {
        ^[
            ["pitch", ["step", "alter", "octave"], ["cents"]],
            ["marking", ["type", "value"], ["placement"]],
            ["spanner", ["type", "edge", "id"], ["direction", "text", "placement"]],
            // `edge` and `id` are allowed here; `prCheckEndpoint`
            // owns the tempo-ramp-only rule.
            ["direction", ["type"],
                ["text", "offset", "unit", "perMinute", "edge", "id"]],
            ["graceNote", ["type", "pitch", "duration"], []],
            ["graceChord", ["type", "pitches", "duration"], []]
        ]
    }

    // Use equality; `includes` compares these Strings by identity.
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

    // `named` means unknown node keys get possible homes in the message.
    *prCheckKeys { |dict, what, allowed, required, named|
        required.do { |key|
            if (dict[key].isNil) {
                Error("ScoreJSONReader: % is missing required key \"%\". Required "
                    "keys are %.".format(
                        what, key, required)).throw
            }
        };
        dict.keys.do { |key|
            var homes = if (named) { this.keyHomes(key) } { [] };
            if (allowed.any { |each| each == key }.not) {
                Error(if (homes.isEmpty) {
                    "ScoreJSONReader: \"%\" is not a schema key for %. Allowed "
                    "keys are %.".format(key, what, allowed)
                } {
                    "ScoreJSONReader: \"%\" is not a key for %. It belongs on %."
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
                    dict["name"], dict["clef"] !? { |c| c.asSymbol },
                    dict["shortName"])
            },
            "measure", {
                // Both fields or neither: partial bars need span and offset.
                if (dict["barDuration"].isNil != dict["metricOffset"].isNil) {
                    Error("ScoreJSONReader: a partial measure needs both "
                        "\"barDuration\" and \"metricOffset\".").throw
                };
                // `clef_` checks the closed clef vocabulary.
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

    // A meter is a pair, and optionally the grouping the pair can't carry.
    //
    // `Meter.grouped` owns grouping legality. Here the wire must be a
    // list of numbers.
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

    // Strict integer parsing: `asInteger` reads "1abc", "1.5" and "01" as 1.
    //
    // >>> ScoreJSONReader.integerValue("12", "a count")   -> 12
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

    // `cents` is fractional, so check decimal shape.
    //
    // Exponent notation is refused. An absent field means no
    // deviation.
    //
    // >>> ScoreJSONReader.floatValue("-0.5", "cents")   -> -0.5
    // >>> ScoreJSONReader.floatValue(nil, "cents")      -> 0
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

    // Leaf payloads rebuild through model factories.
    *decorate { |leaf, dict|
        this.withMarkings(leaf, dict["markings"]);
        this.withSpanners(leaf, dict["spanners"]);
        this.withGraces(leaf, dict["graces"], dict["graceStyle"]);
        ^leaf
    }

    // Grace leaves use their own tags, separate from score elements.
    *withGraces { |leaf, list, style|
        if (list.isNil) {
            if (style.notNil) {
                Error("ScoreJSONReader: \"graceStyle\" requires \"graces\".").throw
            };
            ^leaf
        };
        if (list.isKindOf(String) or: { list.isSequenceableCollection.not }) {
            Error("ScoreJSONReader: \"graces\" must be a list, got %".format(
                list)).throw
        };
        if (list.isEmpty) {
            Error("ScoreJSONReader: \"graces\" cannot be empty. Omit the field "
                "for no grace group.").throw
        };
        ^leaf.graces_(list.collect { |entry| this.graceLeaf(entry) },
            (style ? "grace").asSymbol)
    }

    // Grace duration must already be one note head. Nothing re-cuts a
    // grace group after reading.
    *graceDuration { |pair|
        var dur = this.duration(pair);
        if (dur.isNotatable.not) {
            Error("ScoreJSONReader: grace duration % is not writable as one note "
                "head.".format(pair)).throw
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
                Error("ScoreJSONReader: a grace group cannot hold \"%\". Use "
                    "\"graceNote\" or \"graceChord\".".format(dict["type"])).throw
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
                    "\"id\". Got %".format(entry)).throw
            };
            // Spanner owns meaning; the reader checks wire shape.
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
                    "\"value\". Got %".format(entry)).throw
            };
            this.checkRecord(entry, "marking");
            leaf.attach(this.marking(entry));
        };
        ^leaf
    }

    // Directions belong to the bar, so they are attached to it.
    //
    // This checks wire shape. `Direction` checks musical legality.
    *withDirections { |measure, list|
        if (list.isNil) { ^measure };
        if (list.isKindOf(String) or: { list.isSequenceableCollection.not }) {
            Error("ScoreJSONReader: \"directions\" must be a list, got %".format(
                list)).throw
        };
        list.do { |entry|
            if (entry.isKindOf(Dictionary).not) {
                Error("ScoreJSONReader: a direction must be an object, got %"
                    .format(entry)).throw
            };
            this.checkRecord(entry, "direction");
            if (entry["type"].isNil) {
                Error("ScoreJSONReader: a direction needs a \"type\". Got %"
                    .format(entry)).throw
            };
            this.prCheckEndpoint(entry);
            // Wordless directions need metronome fields, except tempo-ramp stops.
            if (entry["text"].isNil and: { entry["unit"].isNil }
                and: { this.prIsRampStop(entry).not }) {
                Error("ScoreJSONReader: a direction needs \"text\" or a "
                    "metronome mark. Got %".format(
                        entry)).throw
            };
            if (entry["text"].notNil and: { entry["text"].isKindOf(String).not }) {
                Error("ScoreJSONReader: a direction's text must be a string, got "
                    "%".format(entry["text"])).throw
            };
            if (entry["unit"].isNil != entry["perMinute"].isNil) {
                Error("ScoreJSONReader: a metronome mark needs both \"unit\" and "
                    "\"perMinute\". Got %"
                    .format(entry)).throw
            };
            measure.attach(Direction.of(entry["type"].asSymbol, entry["text"],
                entry["offset"] !? { |pair| this.duration(pair) } ? 0,
                entry["unit"] !? { |pair| this.duration(pair) },
                entry["perMinute"] !? { |value|
                    this.positiveInteger(value, "a metronome mark's count")
                },
                nil, nil,
                entry["edge"] !? { |edge| edge.asSymbol },
                entry["id"] !? { |value|
                    this.positiveInteger(value, "a tempo ramp's id")
                }));
        };
        ^measure
    }

    // Since version 23: `edge` and `id` belong only to tempo ramps.
    *prCheckEndpoint { |entry|
        var ramp = entry["type"] == "tempoRamp";
        if (ramp.not) {
            ["edge", "id"].do { |key|
                if (entry[key].notNil) {
                    Error("ScoreJSONReader: a \"%\" direction cannot carry \"%\". "
                        "Only tempoRamp uses endpoints. Got %".format(
                            entry["type"], key, entry)).throw
                }
            };
            ^this
        };
        ["edge", "id"].do { |key|
            if (entry[key].isNil) {
                Error("ScoreJSONReader: a tempoRamp direction needs \"%\". Got %"
                    .format(key, entry)).throw
            }
        };
        if (["start", "stop"].any { |each| each == entry["edge"] }.not) {
            Error("ScoreJSONReader: \"%\" is not a tempo ramp edge. Use start or "
                "stop.".format(entry["edge"])).throw
        };
        ^this
    }

    *prIsRampStop { |entry|
        ^(entry["type"] == "tempoRamp") and: { entry["edge"] == "stop" }
    }

    // Answers one Tuplet, with the counts its bracket was authored
    // with if it carries any. Both counts or neither, and they must
    // match the multiplier.
    *tuplet { |dict, elements|
        var multiplier = this.duration(dict["multiplier"]);
        var actual = dict["actualNotes"];
        var normal = dict["normalNotes"];
        if (actual.isNil and: { normal.isNil }) { ^Tuplet(multiplier, elements) };
        if (actual.isNil or: { normal.isNil }) {
            Error("ScoreJSONReader: a tuplet needs both \"actualNotes\" and "
                "\"normalNotes\", or neither.").throw
        };
        actual = this.positiveInteger(actual, "a tuplet's actual notes");
        normal = this.positiveInteger(normal, "a tuplet's normal notes");
        if (Duration(normal, actual) != multiplier) {
            Error("ScoreJSONReader: tuplet counts %:% do not match multiplier %."
                .format(actual, normal, multiplier)).throw
        };
        ^Tuplet.ratio(actual, normal, elements)
    }

    // Answers one Marking from its object.
    //
    // Text keeps its String. Other values are vocabulary Symbols.
    // `placement` belongs to text alone.
    //
    // Required, not defaulted: a document is a record, not a
    // constructor call.
    //
    // >>> ScoreJSONReader.marking(Dictionary["type" -> "text",
    //     "value" -> "solo", "placement" -> "below"]).placement
    // below
    *marking { |entry|
        var kind = entry["type"].asSymbol;
        var placement = entry["placement"];
        if (kind != \text) {
            if (placement.notNil) {
                Error("ScoreJSONReader: a % marking cannot carry \"placement\"."
                    .format(kind)).throw
            };
            ^Marking.of(kind, entry["value"].asSymbol)
        };
        if (entry["value"].isKindOf(String).not) {
            Error("ScoreJSONReader: text must be a string, got %".format(
                entry["value"])).throw
        };
        if (placement.isNil) {
            Error("ScoreJSONReader: a text marking needs \"placement\" above or "
                "below.").throw
        };
        ^Marking.text(entry["value"], placement.asSymbol)
    }

    // `parseJSON` may hand back Booleans or their Strings. Match exactly.
    //
    // An absent field means "doesn't tie". A JSON null reads the same
    // way, and has to: parseJSON maps both an absent key and an
    // explicit null to nil, so the two can't be told apart here.
    //
    // >>> ScoreJSONReader.boolean("true", "a flag")   -> true
    // >>> ScoreJSONReader.boolean(nil, "a flag")      -> false
    *boolean { |value, what|
        if (value.isNil) { ^false };
        if (value.isKindOf(Boolean)) { ^value };
        if (value == "true") { ^true };
        if (value == "false") { ^false };
        Error("ScoreJSONReader: % must be true or false, got %".format(
            what, value)).throw
    }

    // A chord's tie field is absent, or one flag per pitch, never a
    // bare Boolean. A whole chord tie is an all-true mask, so
    // accepting `true` here would give the same fact two spellings on
    // the wire, and an interchange contract that admits two spellings
    // gets read as one of them by somebody.
    //
    // Strings are sequenceable in sclang, so exclude them before reading masks.
    //
    // >>> ScoreJSONReader.tieMask(["true", "false"])   -> [ true, false ]
    *tieMask { |value|
        if (value.isNil) { ^false };
        if (value.isKindOf(String).not and: { value.isSequenceableCollection }) {
            ^value.collect { |flag| this.boolean(flag, "a chord tie flag") }
        };
        Error("ScoreJSONReader: a chord's tiesToNext must be one flag per pitch, "
            "got %.".format(
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
