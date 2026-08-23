// A bar. Usually it fills its meter, but not always: a pickup is
// short and sits at the *end* of its notional bar, while a final
// incomplete bar is short and sits at the start.
//
// Two exact facts distinguish those: `barDuration` is the written
// span, and `metricOffset` is where that span begins inside the
// nominal meter.
Measure : ScoreContainer {
    var <>meter, <barDuration, <metricOffset;

    // What is said over this bar: tempo, rehearsal mark or instruction.
    //
    // Stored in attachment order and replaced by the setter.
    var <directions;

    // The clef this bar changes to, or nil for "carry on with the one in
    // force".
    //
    // Bar-local because clef changes happen inside a staff.
    //
    // Not a `Direction`: a clef changes how later notes are read.
    var <clef;

    // A String in the children slot is the notation grammar.
    *new { |meter, children|
        if (children.isKindOf(String)) { ^this.notation(meter, children) };
        ^super.new(ScoreNotation.prChildrenOf(children, "Measure"))
            .initMeasure(meter, nil, nil)
    }

    // An explicitly short bar: `barDuration` of written time, beginning
    // `metricOffset` into the meter.
    *partial { |meter, children, barDuration, metricOffset|
        ^super.new(ScoreNotation.prChildrenOf(children, "Measure.partial"))
            .initMeasure(meter, barDuration, metricOffset)
    }

    // An anacrusis: short, and sitting at the end of its notional bar.
    *pickup { |meter, children, barDuration|
        var span = Duration.asDuration(barDuration);
        var full = (Meter.asMeter(meter) ?? { Meter(4, 4) }).duration;
        ^super.new(ScoreNotation.prChildrenOf(children, "Measure.pickup"))
            .initMeasure(meter, span, full - span)
    }

    // A bar of silence: one rest lasting the whole meter.
    //
    // One rest, the shape `wholeBarRests` recognizes.
    //
    // >>> Measure.rest(Meter(3, 4)).wholeBarRests.size   -> 1
    *rest { |meter|
        var bar = Meter.asMeter(meter) ?? { Meter(4, 4) };
        ^this.new(bar, [MusicRest(bar.duration)])
    }

    // A bar from rhythm shares and pitches. `RhythmTree.measure` owns the rule.
    //
    // >>> Measure.proportions(Meter(4, 4), [1, [1, [1, 1, 1]], 2]).leaves.size
    // 5
    *proportions { |meter, proportions, pitches|
        ^RhythmTree.measure(meter, proportions, pitches)
    }

    // The bar written out. `ScoreNotation` owns the grammar; Measure checks the
    // line fills the bar.
    *notation { |meter, text|
        var written;
        if (text.isNil) { ^ScoreNotation.prNotationLine(meter) };
        // A whole line in the meter slot, if it carries a bar half.
        written = if (meter.isKindOf(String)) {
            ScoreNotation.prNotationMeterAndBar(meter) } { nil };
        if (written.notNil and: { written[1].notEmpty }) {
            Error("Measure.notation: \"%\" already includes a bar. Do not also "
                "pass \"%\". Use one String or two arguments.".format(
                    meter, text)).throw
        };
        ^ScoreNotation.prNotation(meter, text)
    }

    // A bar from explicit durations and pitches. The durations must fill it.
    //
    //     Measure.proportions(Meter(4, 4), [1, 1, 2],        [\c, \d, \e]);
    //     Measure.durations(Meter(4, 4), [1%/4, 1%/4, 1%/2], [\c, \d, \e]);
    //
    // Negative durations are rests. Pitches cycle. Durations use the
    // exact duration coercion.
    //
    // No tuplets: use `proportions` when the rhythm needs a bracket.
    //
    // Refuse duration totals that miss the bar span.
    //
    // >>> Measure.durations(Meter(4, 4), [Duration(1, 2), Duration(-1, 2)])
    //     .leaves[1].class   -> MusicRest
    *durations { |meter, durations, pitches|
        var bar = Meter.asMeter(meter) ?? { Meter(4, 4) };
        var pitchStream = RhythmTree.pitchStream(pitches, "Measure.durations");
        var exact = Duration.asDurations(durations);
        var total = exact.inject(Duration(0, 1)) { |sum, each| sum + each.abs };

        if (exact.isEmpty) {
            Error("Measure.durations: duration list cannot be empty. Use "
                "Measure.rest for a silent bar.").throw
        };
        if (total != bar.duration) {
            Error("Measure.durations: durations total %, but a % bar holds %. "
                "Use Measure.partial or Measure.pickup for a short bar.".format(
                    total, bar, bar.duration)).throw
        };
        ^this.new(bar, exact.collect { |each|
            if (each < Duration(0, 1)) {
                MusicRest(each.abs)
            } {
                RhythmTree.noteFrom(pitchStream.next, each)
            }
        })
    }

    initMeasure { |argMeter, argBarDuration, argMetricOffset|
        directions = [];
        meter = Meter.asMeter(argMeter) ?? { Meter(4, 4) };
        barDuration = if (argBarDuration.isNil) {
            meter.duration
        } {
            Duration.asDuration(argBarDuration)
        };
        metricOffset = if (argMetricOffset.isNil) {
            Duration(0, 1)
        } {
            Duration.asDuration(argMetricOffset)
        };
        if (barDuration <= Duration(0, 1)) {
            Error("Measure: bar duration must be positive, got %.".format(
                barDuration)).throw
        };
        if (metricOffset < Duration(0, 1)) {
            Error("Measure: metric offset must be zero or later, got %.".format(
                metricOffset)).throw
        };
        if ((metricOffset + barDuration) > meter.duration) {
            Error("Measure: duration % at offset % runs past the end of %."
                .format(barDuration, metricOffset, meter)).throw
        };
        ^this
    }

    // A bar that fills its meter from the barline is the ordinary case, and
    // nothing downstream needs to treat it specially.
    // >>> RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1]).isPartial   -> false
    isPartial {
        ^(barDuration != meter.duration) or: { metricOffset != Duration(0, 1) }
    }

    // An anacrusis is a partial bar whose span ends at the barline.
    //
    // >>> Measure.pickup(Meter(4, 4), [MusicNote(60, Duration(1, 4))],
    //     Duration(1, 4)).isAnacrusis   -> true
    // >>> Measure.partial(Meter(4, 4), [MusicNote(60, Duration(1, 4))],
    //     Duration(1, 4)).isAnacrusis   -> false
    isAnacrusis {
        ^this.isPartial and: { (metricOffset + barDuration) == meter.duration }
    }

    // A short bar that begins at the barline.
    //
    // >>> Measure.partial(Meter(4, 4), [MusicNote(60, Duration(1, 4))],
    //     Duration(1, 4)).sitsAtBarline   -> true
    sitsAtBarline { ^metricOffset == Duration(0, 1) }

    // Answers this bar, so a direction goes on inline.
    attach { |direction|
        if (direction.isKindOf(Direction).not) {
            Error("Measure: % is not a Direction. Markings belong on leaves."
                .format(direction)).throw
        };
        directions = directions ++ [direction];
        ^this
    }

    // Direction helpers mirror leaf marking helpers and answer this
    // bar. `offset` is how far into this bar the direction stands.
    // `.tempo` may include a metronome mark. `.metronome` is the
    // wordless form.
    tempo { |value, offset = 0, unit, perMinute, beat, bpm|
        ^this.attach(Direction.tempo(value, offset, unit, perMinute, beat, bpm))
    }
    metronome { |unit, perMinute, offset = 0, text, beat, bpm|
        ^this.attach(
            Direction.metronome(unit, perMinute, offset, text, beat, bpm))
    }
    rehearsalMark { |value, offset = 0|
        ^this.attach(Direction.rehearsalMark(value, offset))
    }
    text { |value, offset = 0| ^this.attach(Direction.text(value, offset)) }

    // Tempo-ramp endpoints, one bar at a time. `Direction.tempoRamp`
    // is the ordinary paired form.
    tempoRampStart { |value, offset = 0, id = 1, unit, perMinute, beat, bpm|
        ^this.attach(Direction.tempoRampStart(value, offset, id, unit, perMinute,
            beat, bpm))
    }
    tempoRampStop { |offset = 0, id = 1|
        ^this.attach(Direction.tempoRampStop(offset, id))
    }

    directions_ { |list| directions = (list ? []).asArray.copy; ^this }

    // Answers this, so a clef change can be attached inline.
    clef_ { |value| clef = Staff.checkedClef(value); ^this }
    hasDirections { ^directions.notEmpty }

    // >>> Measure(Meter(4, 4), [
    //     RhythmTree.voice(Meter(4, 4), [1, 1], name: \up),
    //     RhythmTree.voice(Meter(4, 4), [1, 1, 1, 1], name: \down)])
    //     .voices.size   -> 2
    // >>> Measure.rest(Meter(4, 4)).hasVoices   -> false
    hasVoices { ^children.any { |child| child.isKindOf(Voice) } }

    // A bar is either one timeline or a set of voices, never both.
    mixesVoicesWithElements {
        ^this.hasVoices and: {
            children.every { |child| child.isKindOf(Voice) }.not
        }
    }

    // Independent timelines in this bar. A bar with no voices is its
    // one voice.
    voices { ^if (this.hasVoices) { children.asArray } { [this] } }

    // Whole-bar rests: one plain rest, alone in its timeline, lasting
    // the bar. Writers use this tree fact to choose silent-bar
    // notation. Partial bars have none, the shape means a whole
    // silent measure. Attached rests have none; the silent-bar shape
    // cannot carry those facts.
    wholeBarRests {
        if (this.isPartial) { ^[] };
        ^this.voices.collect { |voice|
            var only = if (voice.children.size == 1) { voice.children[0] } { nil };
            if (only.notNil
                and: { only.isKindOf(MusicRest) }
                and: { only.hasMarkings.not }
                and: { only.hasSpanners.not }
                and: { only.hasGraces.not }
                and: { only.dur == barDuration }) { only } { nil }
        }.reject { |leaf| leaf.isNil }
    }

    // Voices run alongside each other, the bar lasts as long as the
    // longest.
    duration {
        ^if (this.hasVoices) {
            this.voices.collect { |voice| voice.duration }
                .reduce { |a, b| if (a > b) { a } { b } }
        } {
            super.duration
        }
    }

    // Every voice must fill the bar's declared span, not the voices
    // between them, and not the meter. A partial bar is full when it
    // holds what it says it does.
    //
    // >>> RhythmTree.measure(Meter(4, 4), [1, 1, 1, 1]).isFull   -> true
    isFull { ^this.voices.every { |voice| voice.duration == barDuration } }

    accept { |writer| ^writer.visitMeasure(this) }

    printOn { |stream|
        stream << "Measure(" << meter;
        if (this.isPartial) { stream << ", " << barDuration << " at " << metricOffset };
        stream << ")"
    }
}
