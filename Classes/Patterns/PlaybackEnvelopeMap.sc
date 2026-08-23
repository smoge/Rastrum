// Note [An envelope is one value, and SC needs telling]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A bare Array in an event key is a multichannel request. This map accepts
// plain arrays and `Ref`-wraps them at output.
//
// The SynthDef must declare `<control>Levels` with `segments + 1` channels and
// `<control>Times` with `segments`.

// Note [An envelope has to fit its event]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// An event-local envelope must fit before the synth frees. Shorter envelopes
// finish and hold their last level. Timeline-wide envelopes are not measured
// against each event.

// Note [One offset can name several events]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A partly tied chord may answer several events at one written offset. An
// offset envelope reaches all of them and must fit each one.

// Note [An ornament is not the note it decorates]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `envelopeAtOffset` targets the written note. Inserted graces at that offset
// are stepped over. Timeline-wide envelopes still reach ornaments.

// Note [An envelope belongs to something sounding]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A rest event can carry envelope keys, but it makes no synth. It cannot be an
// event-local target.

// Note [Every event of the timeline, at one width]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A timeline with an envelope carries the keys on every event. Uncovered events
// hold the value in force. Envelope width is fixed by the SynthDef.


// PlaybackEnvelopeMap: one control moving over the length of one event.
//
// The layer for what a scalar can't say: pan across a held note, cutoff through
// a tied chord, or any control whose value is a shape.
//
// Playback-only. `PlaybackProfile` composes it with the other layers.
PlaybackEnvelopeMap {
    // One entry per envelope set, in set order.
    var entries;

    // Control name to resting value, for events an envelope doesn't cover.
    var baselines;

    *new { ^super.new.init }

    init {
        entries = List.new;
        baselines = IdentityDictionary.new;
        ^this
    }

    // The two keys one control writes.
    //
    // >>> PlaybackEnvelopeMap.keysFor(\pan)   -> [ panLevels, panTimes ]
    *keysFor { |control|
        var name = control.asSymbol.asString;
        ^[(name ++ "Levels").asSymbol, (name ++ "Times").asSymbol]
    }

    // Every event of one timeline takes this envelope.
    //
    // `levels` and `times` are arrays and are Ref-wrapped on output. `times` are
    // beats.
    //
    // >>> PlaybackEnvelopeMap.new.envelopeAt(0, 0, \pan, [-1, 1], [4]).controls
    // [ pan ]
    envelopeAt { |staffIndex, timelineIndex, control, levels, times|
        ^this.prSet(staffIndex, timelineIndex, nil, control, levels, times)
    }

    // One event, found by its absolute offset.
    //
    // A rest is an event, but not an envelope target.
    envelopeAtOffset { |staffIndex, timelineIndex, offset, control, levels, times|
        ^this.prSet(staffIndex, timelineIndex,
            Duration.asDuration(offset), control, levels, times)
    }

    // What uncovered events hold. `PlaybackControlMap` constants win over this.
    baseline { |control, value|
        var name = this.prCheckedControl(control);
        var checked = this.prCheckedNumber(value, "a baseline for " ++ name);

        baselines[name] = checked;
        ^this
    }

    // The controls this map writes, sorted.
    controls {
        ^entries.collect { |entry| entry[\control] }.asArray.as(Set).asArray
            .collect { |each| each.asString }.sort
            .collect { |each| each.asSymbol }
    }

    // Segment width for this control, or nil when this map does not write it.
    //
    // >>> PlaybackEnvelopeMap.new.envelopeAt(0, 0, \pan, [-1, 0, 1], [2, 2]).segments(\pan)
    // 2
    segments { |control|
        var name = control.asSymbol;
        var found = entries.select { |entry| entry[\control] == name };

        if (found.isEmpty) { ^nil };
        ^found.collect { |entry| entry[\times].size }.maxItem
    }

    // Both keys of every control, sorted, as the other layers answer theirs.
    //
    // >>> PlaybackEnvelopeMap.new.carriedKeys   -> [ ]
    // >>> PlaybackEnvelopeMap.new.envelopeAt(0, 0, \pan, [-1, 1], [4]).carriedKeys
    // [ panLevels, panTimes ]
    carriedKeys {
        ^this.controls.collect { |control|
            PlaybackEnvelopeMap.keysFor(control) }.flatten(1)
    }

    // Rebuilt through the public setters, preserving their checks.
    //
    // >>> PlaybackEnvelopeMap.new.baseline(\pan, -0.25).copy.carriedKeys
    // [ ]
    copy {
        var out = PlaybackEnvelopeMap.new;
        baselines.keysValuesDo { |control, value| out.baseline(control, value) };
        entries.do { |entry|
            if (entry[\offset].isNil) {
                out.envelopeAt(entry[\staffIndex], entry[\timelineIndex],
                    entry[\control], entry[\levels].copy, entry[\times].copy)
            } {
                out.envelopeAtOffset(entry[\staffIndex], entry[\timelineIndex],
                    entry[\offset], entry[\control], entry[\levels].copy,
                    entry[\times].copy)
            }
        };
        ^out
    }

    // The events of `element` with every envelope laid over them.
    //
    // >>> PlaybackEnvelopeMap.new.envelopeAt(0, 0, \pan, [-1, 1], [1])
    //     .events(Measure("1/4", "c4")).first.includesKey(\panLevels)
    // true
    events { |element, prepare = true|
        ^this.eventsFrom(Rastrum.events(element, prepare))
    }

    // One Ppar over the timelines. `tempo: false` leaves the clock to a
    // `PlaybackTempoMap`.
    pattern { |element, prepare = true, tempo = true|
        var tree = Rastrum.prepared(element, prepare);
        var music = PatternWriter.pattern(
            this.eventsFrom(Rastrum.events(tree, false)), this.carriedKeys);
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    pbinds { |element, prepare = true|
        ^PatternWriter.pbinds(this.events(element, prepare), this.carriedKeys)
    }

    // The core, over events somebody already has. Copies first, leaving a
    // caller's own alone. What `PlaybackProfile` reaches for.
    eventsFrom { |events|
        var out = events.collect { |event| event.copy };

        if (entries.isEmpty) { ^out };
        this.prCheckTargets(out);
        this.controls.do { |control| this.prApply(out, control) };
        ^out
    }

    // One control across every timeline that asked for it.
    prApply { |events, control|
        var keys = PlaybackEnvelopeMap.keysFor(control);
        var count = this.segments(control);
        var wanted = entries
            .select { |entry| entry[\control] == control }
            .collect { |entry| [entry[\staffIndex], entry[\timelineIndex]] };
        var key = nil;
        var current = nil;

        events.do { |event|
            var payload = event[\rastrum];
            var here = [payload[\staffIndex], payload[\timelineIndex]];
            var found;

            if (wanted.any { |each| each == here }) {
                if (here != key) { key = here; current = nil };
                found = this.prFind(here, control, payload[\offset],
                    payload[\grace] == true);
                if (found.isNil) {
                    // Uncovered: hold the value in force for this event.
                    current ?? { current = this.prBaselineOf(event, control, here) };
                    event[keys[0]] = this.prLevelsRef([current, current], count);
                    event[keys[1]] = this.prTimesRef([event[\dur]], count);
                } {
                    event[keys[0]] = this.prLevelsRef(found[\levels], count);
                    event[keys[1]] = this.prTimesRef(found[\times], count);
                    // A fitting envelope leaves the control at its last level.
                    current = found[\levels].last;
                }
            }
        }
    }

    // Pad to control width and wrap in Ref.
    prLevelsRef { |levels, count|
        var out = levels.copy;
        while { out.size < (count + 1) } { out = out.add(out.last) };
        ^Ref(out)
    }

    // Zero-length tail segments, which an Env reads as no time at all.
    prTimesRef { |times, count|
        var out = times.collect { |each| each.asFloat };
        while { out.size < count } { out = out.add(0.0) };
        ^Ref(out)
    }

    // The envelope for one event: its own if it has one, otherwise the
    // timeline's, otherwise none.
    prFind { |here, control, offset, grace|
        var exact;

        // Inserted ornaments are never exact targets.
        if (grace.not) {
            exact = entries.detect { |entry|
                (entry[\control] == control)
                    and: { entry[\staffIndex] == here[0] }
                    and: { entry[\timelineIndex] == here[1] }
                    and: { entry[\offset].notNil }
                    and: { entry[\offset] == offset } };
            if (exact.notNil) { ^exact };
        };
        ^entries.detect { |entry|
            (entry[\control] == control)
                and: { entry[\staffIndex] == here[0] }
                and: { entry[\timelineIndex] == here[1] }
                and: { entry[\offset].isNil } }
    }

    // What an uncovered event holds: control-map value, then baseline, then
    // refusal.
    prBaselineOf { |event, control, here|
        var scalar = event[control];

        if (scalar.notNil and: { scalar.isNumber }) { ^scalar };
        baselines[control] !? { |value| ^value };
        Error("PlaybackEnvelopeMap: staff % timeline % has an envelope for % on "
            "part of it and no value for the rest. Add baseline(%, value) or a "
            "constant PlaybackControlMap value.".format(here[0], here[1], control,
                control.asCompileString)).throw
    }

    // Check targets and event-local fit before writing any keys.
    prCheckTargets { |events|
        var found = List.new;

        events.do { |event, index|
            var payload = this.prPayloadOf(event, index);
            found.add(IdentityDictionary[
                \key -> [payload[\staffIndex], payload[\timelineIndex]],
                \offset -> payload[\offset], \dur -> event[\dur],
                \rest -> (payload[\rest] == true),
                \grace -> (payload[\grace] == true)]);
        };
        entries.do { |entry| this.prCheckEntry(entry, found) };
    }

    prCheckEntry { |entry, found|
        var here = [entry[\staffIndex], entry[\timelineIndex]];
        var inTimeline = found.select { |each| each[\key] == here };
        var at = entry[\offset];
        var atMoment, sounding;

        if (inTimeline.isEmpty) {
            Error("PlaybackEnvelopeMap: this score has no staff % timeline %, "
                "so the % envelope reaches nothing."
                .format(here[0], here[1], entry[\control])).throw
        };
        // Timeline-wide envelopes are not measured against single events.
        if (at.isNil) { ^this };
        // Every written, sounding event at that moment must fit.
        atMoment = inTimeline.select { |each| each[\offset] == at };
        sounding = atMoment.select { |each|
            each[\grace].not and: { each[\rest].not } };

        if (atMoment.isEmpty) {
            Error("PlaybackEnvelopeMap: no event of staff % timeline % begins "
                "at %, so the % envelope reaches nothing.".format(
                    here[0], here[1], at, entry[\control])).throw
        };
        if (sounding.isEmpty) {
            // A rest is an event, but not a target.
            if (atMoment.any { |each| each[\rest] }) {
                Error("PlaybackEnvelopeMap: staff % timeline % rests at %, and "
                    "the % envelope has no synth to move.".format(
                        here[0], here[1], at, entry[\control])).throw
            };
            Error("PlaybackEnvelopeMap: the only thing staff % timeline % has "
                "at % is an inserted ornament. Target a written note.".format(
                    here[0], here[1], at)).throw
        };
        sounding.do { |target| this.prCheckFits(entry, target) };
    }

    // Note [An envelope has to fit its event]. Both sides are beats.
    //
    // Slack is only for floating-point sums.
    prCheckFits { |entry, target|
        var span = entry[\times].sum;

        if (span > (target[\dur] + 1e-9)) {
            Error("PlaybackEnvelopeMap: the % envelope at % lasts % beats and "
                "the event lasts %. Shorten it or target a longer event.".format(
                    entry[\control], target[\offset], span, target[\dur])).throw
        }
    }

    prPayloadOf { |event, index|
        var payload = event !? { event[\rastrum] };
        if (payload.isNil) {
            Error("PlaybackEnvelopeMap: event % has no \\rastrum payload. Use "
                "EventWriter."
                .format(index)).throw
        };
        [\staffIndex, \timelineIndex, \offset].do { |key|
            if (payload[key].isNil) {
                Error("PlaybackEnvelopeMap: event % payload has no %. Use "
                    "EventWriter.".format(index, key)).throw
            }
        };
        ^payload
    }

    // Checked into locals first, so a refused call leaves the map unchanged.
    // Setting the same control on the same target twice replaces it, as a table
    // entry would.
    prSet { |staffIndex, timelineIndex, offset, control, levels, times|
        var target = this.prCheckedTarget(staffIndex, timelineIndex);
        var name = this.prCheckedControl(control);
        var checkedLevels = this.prCheckedLevels(levels, times, name);
        var checkedTimes = this.prCheckedTimes(times, name);
        var already = entries.detect { |entry|
            (entry[\control] == name)
                and: { entry[\staffIndex] == target[0] }
                and: { entry[\timelineIndex] == target[1] }
                and: { entry[\offset] == offset } };

        already !? { |entry| entries.remove(entry) };
        entries.add(IdentityDictionary[
            \staffIndex -> target[0], \timelineIndex -> target[1],
            \offset -> offset, \control -> name,
            \levels -> checkedLevels, \times -> checkedTimes]);
        ^this
    }

    prCheckedTarget { |staffIndex, timelineIndex|
        [staffIndex, timelineIndex].do { |value, position|
            if (value.isKindOf(Integer).not or: { value < 0 }) {
                Error("PlaybackEnvelopeMap: % index must be a non-negative "
                    "Integer, got %.".format(["staff", "timeline"][position],
                        value.asCompileString)).throw
            }
        };
        ^[staffIndex, timelineIndex]
    }

    // A control this map may move. The refused ones are `PlaybackControlMap`'s
    // reserved list, on the same three grounds, plus the derived keys, so a
    // name that is harmless itself can't spell one that isn't.
    //
    // `\amp` is among them, and stays there. A loudness envelope is
    // `PlaybackMap`'s to write and that map now writes one, by
    // Note [Loudness as breakpoints] there: `useLoudnessEnvelopes` lowers a
    // dynamic's transitions to `ampLevels` and `ampTimes` of this same shape.
    // Two layers describing the same sound is the ambiguity the reserved list
    // exists to prevent, so both derived keys are reserved beside it.
    prCheckedControl { |control|
        var name, reserved;
        if (control.isNil) {
            Error("PlaybackEnvelopeMap: envelope control cannot be nil.").throw
        };
        name = control.asSymbol;
        reserved = PlaybackControlMap.reservedKeys;
        if (reserved.includes(name)) {
            Error("PlaybackEnvelopeMap: % is reserved and cannot be enveloped."
                .format(name)).throw
        };
        PlaybackEnvelopeMap.keysFor(name).do { |key|
            if (reserved.includes(key)) {
                Error("PlaybackEnvelopeMap: an envelope for % would write %, "
                    "which is reserved.".format(name, key)).throw
            }
        };
        ^name
    }

    // One more level than there are segments, which is what an `Env` is. Two
    // levels and one time is the shortest thing that moves.
    prCheckedLevels { |levels, times, control|
        var list = this.prCheckedNumbers(levels, "levels", control);
        var wanted = this.prCheckedNumbers(times, "times", control).size + 1;

        if (list.size < 2) {
            Error("PlaybackEnvelopeMap: an envelope for % needs at least two "
                "levels.".format(control)).throw
        };
        if (list.size != wanted) {
            Error("PlaybackEnvelopeMap: an envelope for % needs levels = times "
                "+ 1, got % and %.".format(control,
                    list.size, wanted - 1)).throw
        };
        ^list
    }

    // Zero is allowed and is a jump. Negative isn't a length.
    prCheckedTimes { |times, control|
        var list = this.prCheckedNumbers(times, "times", control);
        list.do { |each|
            if (each < 0) {
                Error("PlaybackEnvelopeMap: a segment of an envelope for % "
                    "lasts % beats. Times must be zero or more.".format(
                        control, each)).throw
            }
        };
        ^list
    }

    prCheckedNumbers { |values, what, control|
        var list;
        if (values.isKindOf(Ref)) {
            Error("PlaybackEnvelopeMap: the % of an envelope for % are written "
                "as a plain array, not a Ref.".format(
                    what, control)).throw
        };
        // Ordered, and a String is sequenceable without being numbers. An
        // envelope is a shape, so a Set or a Dictionary would be read in
        // whatever order it happened to hold and the shape would be arbitrary
        // rather than wrong in any way a caller could see.
        if (values.isSequenceableCollection.not
            or: { values.isKindOf(String) }) {

            Error("PlaybackEnvelopeMap: the % of an envelope for % must be an "
                "ordered Array of numbers, got %.".format(
                    what, control, values.asCompileString)).throw
        };
        list = values.asArray;
        list.do { |each|
            if (each.isNumber.not) {
                Error("PlaybackEnvelopeMap: % in % for % is not a number."
                    .format(each.asCompileString, what, control)).throw
            }
        };
        ^list
    }

    prCheckedNumber { |value, what|
        if (value.isNumber.not) {
            Error("PlaybackEnvelopeMap: % is %, not a number.".format(
                what, value.asCompileString)).throw
        };
        ^value
    }
}
