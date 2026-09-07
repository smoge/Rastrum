// A complete score: staves plus the title and composer a page is headed with.
//
// The only root ScoreJSON accepts. Bare `Measure` is a fragment there. The
// envelope is a document fact, not music content.
MusicScore : ScoreContainer {
    var <>title, <>composer;

    *new { |children, title, composer|
        ^super.new(children).initMusicScore(title, composer)
    }

    // Short spelling for `MusicScore([Staff([bar])])`, one measure or many.
    //
    // >>> MusicScore.oneStaff(Measure(Meter(1, 4), [MN(60, Duration(1, 4))])).leaves.size
    // 1
    *oneStaff { |measures, name, clef = \treble, title, composer|
        ^this.new([Staff(this.prMeasuresOf(measures), name, clef)],
            title, composer)
    }

    // A written String is a bar run rather than one bar. `|` separates bars and
    // a meter carries forward. `asArray` on a String answers its characters, so
    // the String case is answered before the list case.
    //
    // Adjacent lines in a list join into one run for the same reason. That lets
    // a later line drop the meter. A built `Measure` breaks the carry: it may
    // have come from anywhere, so the next line states its own meter.
    //
    // `place` names the staff or voice a refusal came from and is nil where
    // there is no coordinate to add.
    //
    // >>> MusicScore.oneStaff("2/4; c4 d4 | e2", "V").children.first.children.size
    // 2
    // >>> MusicScore.oneStaff(["2/4; c4 d4", "e2"], "V").leaves.size   -> 3
    *prMeasuresOf { |measures, place|
        var bars = [], lines = [], flush;
        if (measures.isKindOf(String)) { ^this.prBarRun(measures, place) };
        if (measures.isKindOf(Measure)) { ^[measures] };
        if (measures.isSequenceableCollection.not) {
            Error(
                "% measures must be a bar-run String, a Measure or a list of those, got a %."
                .format(this.prBodyAt(place), measures.class)
            ).throw
        };
        // A staff body names at least one bar. `""` is already refused for
        // that. An empty list says it in the other spelling and would build a
        // staff holding nothing.
        if (measures.isEmpty) {
            Error(
                "% measures is an empty list, which names no bars. A staff body is one bar or more."
                .format(this.prBodyAt(place))
            ).throw
        };
        flush = {
            if (lines.notEmpty) {
                bars = bars ++ this.prBarRun(lines.join(" | "), place);
                lines = []
            }
        };
        measures.do { |each|
            if (each.isKindOf(String)) {
                lines = lines.add(each)
            } {
                if (each.isKindOf(Measure).not) {
                    Error(
                        "% measures holds a %. A bar is a Measure or a written line."
                        .format(this.prBodyAt(place), each.class)
                    ).throw
                };
                flush.value;
                bars = bars.add(each)
            }
        };
        flush.value;
        ^bars
    }

    // A body refusal keeps `ScoreNotation.measureRun`'s own wording. Only the
    // coordinate is added. The inner message already owns the bar number.
    //
    // The throw is caught into a var rather than answered from the handler
    // because sclang unwinds over a receiver an argument list has already
    // pushed.
    *prBarRun { |text, place|
        var bars, failure;
        try { bars = ScoreNotation.measureRun(text) } { |error| failure = error };
        if (failure.notNil) {
            if (place.isNil) { failure.throw };
            Error(
                "MusicScore.staves: %: %"
                .format(
                    place,
                    failure.errorString.replace("ERROR: ", "")
                )
            ).throw
        };
        ^bars
    }

    // A refusal names what was written.
    //
    // An index is the fallback where nothing was named.
    //
    // >>> MusicScore.prPlaceOf("staff", "Cello", 1)   -> staff "Cello"
    // >>> MusicScore.prPlaceOf("voice", nil, 1)   -> voice 1
    *prPlaceOf { |kind, name, index|
        if (name.isNil) { ^"% %".format(kind, index) };
        ^"% %".format(kind, name.asCompileString)
    }

    // `oneStaff` has one staff, so it names itself and no coordinate
    //
    // >>> MusicScore.prBodyAt(nil)   -> MusicScore.oneStaff:
    *prBodyAt { |place|
        if (place.isNil) { ^"MusicScore.oneStaff:" };
        ^"MusicScore.staves: %".format(place)
    }

    // The same shortening for several staves: one Event each, written `(name:,
    // shortName:, clef:, measures:)`, where a staff body is one timeline
    // written as bars or `voices:` for named parallel ones:
    //
    //   MusicScore.staves([
    //       (name: "Flute", clef: \treble,
    //           measures: "4/4; c'4 d'4 e'4 f'4 | 3:2[g'4 a'4 b'4] c''4 r4"),
    //       (name: "Cello", clef: \bass, measures: [
    //           "4/4 c,4 r4 e,4 g,4", Measure.rest("4/4")])
    //   ], "Study")
    //
    // `staves`, not `parts`: grouping staves into parts is not modeled here.
    //
    // >>> MusicScore.staves([(measures: Measure("1/4", "c4"))]).children.first.clef
    // treble
    *staves { |specs, title, composer|
        ^this.new(specs.asArray.collect { |spec, at|
            this.prStaffSpec(spec, at) }, title, composer)
    }

    // Every key is checked. A body is one shape or the other.
    *prStaffSpec { |spec, index = 0|
        var place, measures;
        if (spec.isKindOf(Event).not) {
            Error(
                "MusicScore.staves: expected a staff spec Event, got a %."
                .format(spec.class)
            ).throw
        };
        place = this.prPlaceOf("staff", spec[\name], index);
        this.prCheckedKeys(spec, [\name, \shortName, \clef, \measures, \voices],
            place, "staff", "name, shortName, clef, measures and voices");
        if (spec[\measures].notNil and: { spec[\voices].notNil }) {
            Error(
                "MusicScore.staves: % uses both measures and voices. A staff body is "
                "one timeline or a set of named voices."
                .format(place)
            ).throw
        };
        measures = if (spec[\voices].notNil) {
            this.prMeasuresFromVoices(spec[\voices], place)
        } {
            if (spec[\measures].isNil) {
                Error("MusicScore.staves: % has no measures.".format(place)).throw
            };
            this.prMeasuresOf(spec[\measures], place)
        };
        // `clef: nil` reads as the default in an Event
        ^Staff(measures, spec[\name], spec[\clef] ? \treble, spec[\shortName])
    }

    // A misspelled key would otherwise leave a slot unset and build a staff
    // holding nothing.
    *prCheckedKeys { |spec, allowed, place, kind, use|
        var unknown = spec.keys.asArray.reject { |key|
            allowed.includes(key) }.sort;
        if (unknown.notEmpty) {
            Error(
                "MusicScore.staves: % has unknown % spec key(s): %. Use %."
                .format(
                    place,
                    kind,
                    unknown.collect { |key| key.asString }.join(", "),
                    use
                )
            ).throw
        }
    }

    // A staff writes one body per voice and this interleaves them: bar `n` of
    // every voice becomes the timelines of one bar. They begin at the same
    // barline.
    //
    // They must agree on bar count and on each bar's meter and partial-bar
    // frame. Agreeing on the frame is what lets an anacrusis be shared rather
    // than turned into a full bar that is not full.
    //
    // Interleaving is the shape no constructor reaches directly. That is why it
    // earns a key where a written body only saves a call.
    //
    // >>> MusicScore.staves([(voices: [(measures: "1/4; c4"), (measures: "1/4; e4")])]).children.first.children.first.voices.size
    // 2
    *prMeasuresFromVoices { |voices, place|
        var rows, count;
        if (voices.isSequenceableCollection.not or: {
            voices.isKindOf(String) }) {
            Error(
                "MusicScore.staves: % voices must be a list of voice spec Events, got a %.".format(place, voices.class)
            ).throw
        };
        if (voices.isEmpty) {
            Error(
                "MusicScore.staves: % has no voices. Use measures for one timeline.".format(place)
            ).throw
        };
        rows = voices.asArray.collect { |spec, at|
            this.prVoiceRow(spec, place, at) };
        count = rows.first[\bars].size;
        rows.do { |row|
            if (row[\bars].size != count) {
                Error(
                    "MusicScore.staves: %, % has % bar(s), expected %."
                    .format(place, row[\place], row[\bars].size, count)
                ).throw
            }
        };
        ^count.collect { |at| this.prVoicedBar(rows, at, place) }
    }

    // One voice spec, read the way a staff spec is.
    *prVoiceRow { |spec, place, index|
        var voicePlace;
        if (spec.isKindOf(Event).not) {
            Error(
                "MusicScore.staves: %, expected a voice spec Event, got a %.".format(place, spec.class)
            ).throw
        };
        voicePlace = this.prPlaceOf("voice", spec[\name], index);
        this.prCheckedKeys(spec, [\name, \measures],
            "%, %".format(place, voicePlace), "voice", "name and measures");
        if (spec[\measures].isNil) {
            Error(
                "MusicScore.staves: %, % has no measures.".format(place, voicePlace)
            ).throw
        };
        ^(name: spec[\name], place: voicePlace,
            bars: this.prMeasuresOf(spec[\measures],
                "%, %".format(place, voicePlace)))
    }

    // The first voice's bar is the frame every other one is read against.
    *prVoicedBar { |rows, index, place|
        var frame = rows.first[\bars][index];
        var timelines = rows.collect { |row|
            var bar = row[\bars][index];
            this.prCheckedVoiceBar(bar, frame, row, index, place);
            Voice(bar.children.asArray, row[\name])
        };
        if (frame.isPartial) {
            ^Measure.partial(frame.meter, timelines, frame.barDuration,
                frame.metricOffset)
        };
        ^Measure(frame.meter, timelines)
    }

    // The rebuild keeps only the meter and frame.
    //
    // Every fact it cannot carry is refused here rather than dropped.
    *prCheckedVoiceBar { |bar, frame, row, index, place|
        var at = index + 1;
        if (bar.hasVoices) {
            Error(
                "MusicScore.staves: %, % bar % already holds voices.".format(place, row[\place], at)
            ).throw
        };
        if (bar.meter != frame.meter) {
            Error(
                "MusicScore.staves: %, bar % has different meters across voices.".format(place, at)
            ).throw
        };
        if (bar.barDuration != frame.barDuration or: {
            bar.metricOffset != frame.metricOffset }) {
            Error(
                "MusicScore.staves: %, bar % has different partial-bar spans across voices.".format(place, at)
            ).throw
        };
        if (bar.hasDirections or: { bar.clef.notNil }) {
            Error(
                "MusicScore.staves: %, % bar % carries bar facts this rebuild cannot keep. Build that Measure directly."
                .format(place, row[\place], at)
            ).throw
        }
    }

    initMusicScore { |argTitle, argComposer|
        title = argTitle;
        composer = argComposer;
        ^this
    }

    accept { |writer| ^writer.visitScore(this) }

    // Staff count and title, for the same reason `Staff` says bar count and
    // name. `a MusicScore` said neither.
    //
    // >>> MusicScore.oneStaff(Measure("2/4", "c4 d4"), "Violin", \treble, "Study I").asString   -> MusicScore(1, Study I)
    // >>> MusicScore([]).asString   -> MusicScore(0)
    printOn { |stream|
        stream << "MusicScore(" << children.size;
        if (title.notNil) { stream << ", " << title };
        stream << ")"
    }

    // >>> MusicScore([], "Study I").asCompileString   -> MusicScore([], "Study I")
    storeArgs { ^[children.asArray, title, composer] }

    // No asLily / asMusicXML / asJSON convenience methods: the model
    // names no output format.
}
