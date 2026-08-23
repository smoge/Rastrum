// ScoreJSONWriter: writes the ScoreJSON interchange document.
//
// The envelope and schema constants live in ScoreJSONContract. The
// class methods here stay as the public writer-facing API.
ScoreJSONWriter : ScoreWriter {
    *schemaFormat { ^ScoreJSONContract.schemaFormat }
    *schemaVersion { ^ScoreJSONContract.schemaVersion }

    // A document is a whole score. Other roots would write fragments
    // the reader refuses.
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
            Error("ScoreJSONWriter: expected a MusicScore document, got a %. "
                "Wrap fragments in MusicScore before writing.".format(
                    element.class)).throw
        };
        ^super.write(element)
    }

    // JSON strings cannot contain raw control characters, so escape
    // them here.
    //
    // Named escapes stay short; the rest use \uXXXX. Escape backslash
    // first. Bytes above 127 pass through as UTF-8.
    //
    // >>> ScoreJSONWriter.escape("a\"b").asCompileString   -> "a\\\"b"
    *escape { |string|
        var digits = "0123456789abcdef";
        var out = String.new;
        var quote = RastrumChar.doubleQuote, backslash = RastrumChar.backslash;
        string.asString.do { |char|
            var code = char.ascii;
            case
                { char == backslash } { out = out ++ "\\\\" }
                { char == quote     } { out = out ++ "\\\"" }
                { char == Char.nl   } { out = out ++ "\\n" }
                { char == Char.ret  } { out = out ++ "\\r" }
                { char == Char.tab  } { out = out ++ "\\t" }
                { code == 8  }        { out = out ++ "\\b" }
                { code == 12 }        { out = out ++ "\\f" }
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
        this.writeString("shortName", staff.shortName);
        this.writeString("clef", staff.clef);
        this.writeElements(staff, "measures");
        stream << "}";
    }

    // Partial-bar fields are written only when the bar is short.
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

    // Counts travel only when they say something the multiplier does not.
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

    // A bar with voices carries them as explicit nodes. One without
    // holds its elements directly.
    visitVoice { |voice|
        stream << "{\"type\":\"voice\"";
        this.writeString("name", voice.name);
        this.writeElements(voice);
        stream << "}";
    }

    // Tie data is written only when there is any. An absent field
    // reads as "doesn't tie".
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

    // A chord writes the whole tie mask when anything ties.
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

    // Written in attachment order. Tempo-ramp endpoints carry `edge` and `id`.
    writeDirections { |measure|
        if (measure.directions.isEmpty) { ^this };
        stream << ",\"directions\":[";
        measure.directions.do { |direction, i|
            if (i > 0) { stream << "," };
            stream << "{\"type\":\"" << direction.kind << "\"";
            direction.edge !? { |edge|
                stream << ",\"edge\":\"" << edge << "\",\"id\":" << direction.id
            };
            // Absent on wordless metronome marks and ramp stops.
            direction.text !? { |text|
                stream << ",\"text\":\"" << ScoreJSONWriter.escape(text) << "\""
            };
            // Omitted at zero, the default direction position.
            if (direction.atBarStart.not) {
                stream << ",\"offset\":";
                this.writePair(direction.offset);
            };
            // Both or neither, in written order: `4 = 132`.
            if (direction.unit.notNil) {
                stream << ",\"unit\":";
                this.writePair(direction.unit);
                stream << ",\"perMinute\":" << direction.perMinute;
            };
            stream << "}";
        };
        stream << "]";
        ^this
    }

    // Written only when there are any, and in attachment order. A
    // dynamic before an articulation isn't the same instruction as
    // the reverse.
    writeMarkings { |leaf|
        if (leaf.markings.isEmpty) { ^this };
        stream << ",\"markings\":[";
        leaf.markings.do { |marking, i|
            if (i > 0) { stream << "," };
            // Escape every value; text is the one expected to need it.
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

    // Since version 18: Write grace group and non-default style only
    // when present.
    //
    // Grace leaves have their own tags; their durations are note heads.
    writeGraces { |leaf|
        if (leaf.hasGraces.not) { ^this };
        // Raw writers may skip validation; refuse facts this method cannot write.
        leaf.graces.do { |grace|
            var tied;
            // Check kind before asking grace-specific questions.
            if (grace.isKindOf(MusicNote).not and: { grace.isKindOf(Chord).not }) {
                Error("ScoreJSONWriter: a grace group may hold only notes and "
                    "chords, got a %.".format(grace.class)).throw
            };
            tied = if (grace.isKindOf(Chord)) {
                grace.tiedPitches.notEmpty
            } {
                grace.tiesToNext
            };
            if (tied or: { grace.hasMarkings } or: { grace.hasSpanners }
                or: { grace.hasGraces }) {
                Error("ScoreJSONWriter: a grace leaf cannot carry ties, markings, "
                    "spanners or nested graces.").throw
            };
            // Grace durations must already be single note heads.
            if (grace.dur.isNotatable.not) {
                Error("ScoreJSONWriter: grace duration % must be writable as one "
                    "note head.".format(grace.dur)).throw
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
