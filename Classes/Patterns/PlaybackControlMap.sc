

// Note [One constant per timeline]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A constant has no curve. It is written on every event of its
// targeted timeline, so `PatternWriter`'s partial-key refusal is
// unreachable from here. Note-local values are still constants. Only
// the targeted event changes.

// Note [A local value needs something to return to]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A key on one event of a timeline and nil on the rest ends the
// `Pbind` stream there, so `controlAtOffset` writes every event of
// that timeline. `prValueOf` reads, in order:
//
//     the override at this offset, unless this event is an inserted
//     grace the timeline's own constant, being the more specific
//     baseline(control, value) refuse
//
// Nothing is guessed. A baseline alone writes nothing. It only says
// what an override returns to. An offset names every sounding event
// at that moment, a partly tied chord being several by Note [Ties
// become sounding runs] in Patterns/EventWriter.sc. It may not name a
// rest, an inserted grace or an offset no event begins at.

// Note [What this map may not write]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Refused keys are structural event keys, Rastrum payload names, SC
// keys that override pitch or duration, and keys owned by another
// playback layer. A control map decorates events. It must not rewrite
// what they are or race a peer layer for the same key. The lists are
// literals because class initialization order is not a contract.
// Tests compare them against the source lists.

// Note [A name is not resolved until a score arrives]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// `panAt(0, 1, ...)` says which timeline by number, and a score edit that
// inserts a staff makes it mean a different one silently. `panFor("Piano",
// "lower", ...)` says which by name, and survives that edit or refuses.
//
// Names stay unresolved until `prResolved`, where the score is known. Exactly
// one timeline may match.
//
// Unlike `PlaybackMap`, index and name targets do not override each other.
// Same value means one thing twice; different values are refused.


// PlaybackControlMap: constant SC Event keys per timeline or per note, laid
// over structural events.
//
// It inserts no event, moves no attack and reads no marking. It
// answers routing and constant control values beside the score.
// `controlAt` targets a whole timeline. `controlAtOffset` targets one
// written event and returns to a baseline around it. Moving controls
// belong to `PlaybackEnvelopeMap`.
//
// Targets may be indexes or staff/voice names.
//
// See Note [A name is not resolved until a score arrives].
//
// Composed elsewhere. `PlaybackProfile` decides layer order and calls
// `eventsFrom`.
PlaybackControlMap {
    // [staffIndex, timelineIndex] -> IdentityDictionary of key -> value.
    var controls;

    // Note-local overrides, in set order.
    var overrides;

    // Control name to resting value, for events an override doesn't cover.
    var baselines;

    // Named targets, unresolved until events reveal the timeline.
    var namedControls;

    classvar structuralKeys, payloadKeys, interpretedKeys;

    *initClass {
        structuralKeys = [\type, \midinote, \dur, \rastrum,
            \freq, \note, \degree, \sustain, \stretch, \delta];
        payloadKeys = [\offset, \duration, \staffIndex, \timelineIndex,
            \measure, \rest, \markings, \spanners, \staff, \voice];
        interpretedKeys = [\instrument, \amp, \ampEnd, \ampRampStart,
            \ampRampDur, \ampLevels, \ampTimes, \legato, \tempo];
    }

    // Copies, see Note [Maps expose guarded copies] in
    // Patterns/PlaybackMap.sc.
    *structuralKeys { ^structuralKeys.copy }
    *payloadKeys { ^payloadKeys.copy }
    *interpretedKeys { ^interpretedKeys.copy }

    // >>> PlaybackControlMap.reservedKeys.includes(\dur)   -> true
    // >>> PlaybackControlMap.reservedKeys.includes(\pan)   -> false
    *reservedKeys { ^structuralKeys ++ payloadKeys ++ interpretedKeys }

    *new { ^super.new.init }

    init {
        controls = Dictionary.new;
        overrides = List.new;
        baselines = IdentityDictionary.new;
        namedControls = List.new;
        ^this
    }

    // Answers this, so calls chain. Checked into a local first, so a refused
    // call leaves the map as it was.
    //
    // >>> PlaybackControlMap.new.panAt(0, 0, -0.5).controlsAt(0, 0)[\pan]   -> -0.5
    panAt { |staffIndex, timelineIndex, value|
        var checked = this.prCheckedPan(value);
        ^this.prSet(staffIndex, timelineIndex, \pan, checked)
    }

    // >>> PlaybackControlMap.new.outAt(0, 0, 2).controlsAt(0, 0)[\out]   -> 2
    outAt { |staffIndex, timelineIndex, value|
        var checked = this.prCheckedBus(value, \out);
        ^this.prSet(staffIndex, timelineIndex, \out, checked)
    }

    // >>> PlaybackControlMap.new.groupAt(0, 0, 3).controlsAt(0, 0)[\group]   -> 3
    groupAt { |staffIndex, timelineIndex, value|
        var checked = this.prCheckedGroup(value);
        ^this.prSet(staffIndex, timelineIndex, \group, checked)
    }

    // Anything else the timeline's SynthDef declares. The key is
    // checked by Note [What this map may not write], and the value
    // only has to be a constant that isn't nil.
    //
    // >>> PlaybackControlMap.new.controlAt(0, 0, \cutoff, 800).carriedKeys
    // [ cutoff ]
    controlAt { |staffIndex, timelineIndex, key, value|
        var checkedKey = this.prCheckedKey(key);
        var checkedValue = this.prCheckedValue(checkedKey, value);
        ^this.prSet(staffIndex, timelineIndex, checkedKey, checkedValue)
    }

    // The same controls, targeted by score names rather than indexes.
    //
    // `voiceName` is nil for a bar without explicit voices.
    //
    // >>> PlaybackControlMap.new.panFor("Violin", nil, -0.5).carriedKeys
    // [ pan ]
    panFor { |staffName, voiceName, value|
        var checked = this.prCheckedPan(value);
        ^this.prSetNamed(staffName, voiceName, \pan, checked)
    }

    // >>> PlaybackControlMap.new.outFor("Violin", nil, 2).carriedKeys   -> [ out ]
    outFor { |staffName, voiceName, value|
        var checked = this.prCheckedBus(value, \out);
        ^this.prSetNamed(staffName, voiceName, \out, checked)
    }

    // >>> PlaybackControlMap.new.groupFor("Violin", nil, 3).carriedKeys
    // [ group ]
    groupFor { |staffName, voiceName, value|
        var checked = this.prCheckedGroup(value);
        ^this.prSetNamed(staffName, voiceName, \group, checked)
    }

    // >>> PlaybackControlMap.new.controlFor("Violin", nil, \cutoff, 800)
    //     .namedControls.first
    // [ Violin, nil, cutoff, 800 ]
    controlFor { |staffName, voiceName, key, value|
        var checkedKey = this.prCheckedKey(key);
        var checkedValue = this.prCheckedValue(checkedKey, value);
        ^this.prSetNamed(staffName, voiceName, checkedKey, checkedValue)
    }

    // One written event, found by absolute offset. The rest of the
    // timeline takes the resting value. The same key checks as
    // `controlAt`, and the same value rule: a constant that isn't
    // nil.
    //
    // >>> PlaybackControlMap.new.controlAtOffset(0, 0, 0, \cutoff, 800).carriedKeys
    // [ cutoff ]
    controlAtOffset { |staffIndex, timelineIndex, offset, key, value|
        var target = this.prCheckedTarget(staffIndex, timelineIndex);
        var at = Duration.asDuration(offset);
        var checkedKey = this.prCheckedKey(key);
        var checkedValue = this.prCheckedValue(checkedKey, value);
        var already = overrides.detect { |entry|
            (entry[\key] == checkedKey)
                and: { entry[\staffIndex] == target[0] }
                and: { entry[\timelineIndex] == target[1] }
                and: { entry[\offset] == at } };

        already !? { |entry| overrides.remove(entry) };
        overrides.add(IdentityDictionary[
            \staffIndex -> target[0], \timelineIndex -> target[1],
            \offset -> at, \key -> checkedKey, \value -> checkedValue]);
        ^this
    }

    // Resting value for uncovered events. Inert until a control uses it.
    //
    // >>> PlaybackControlMap.new.baseline(\cutoff, 400).carriedKeys   -> [ ]
    baseline { |control, value|
        var checkedKey = this.prCheckedKey(control);
        var checkedValue = this.prCheckedValue(checkedKey, value);

        baselines[checkedKey] = checkedValue;
        ^this
    }

    // One timeline's constants as a copy, nil where it has none.
    // Overrides are not among them: a constant is what the timeline
    // rests at.
    //
    // >>> PlaybackControlMap.new.controlsAt(0, 0)   -> nil
    controlsAt { |staffIndex, timelineIndex|
        ^controls[[staffIndex, timelineIndex]] !? { |table| table.copy }
    }

    // One timeline's note-local overrides as `offset -> value`, nil where that
    // key has none there.
    //
    // >>> PlaybackControlMap.new.controlAtOffset(0, 0, "1/4", \cutoff, 800)
    //     .overridesAt(0, 0, \cutoff)[Duration(1, 4)]
    // 800
    overridesAt { |staffIndex, timelineIndex, key|
        var name = key.asSymbol;
        var found = overrides.select { |entry|
            (entry[\key] == name)
                and: { entry[\staffIndex] == staffIndex }
                and: { entry[\timelineIndex] == timelineIndex } };
        var out = Dictionary.new;

        if (found.isEmpty) { ^nil };
        found.do { |entry| out[entry[\offset]] = entry[\value] };
        ^out
    }

    // The resting values this map was given, as a copy.
    baselines { ^baselines.copy }

    // Every named target as a copy, `[staffName, voiceName, key,
    // value]`, in the order they were set. Matches
    // `PlaybackMap.namedInstruments`.
    namedControls { ^namedControls.collect { |entry| entry.copy }.asArray }

    // Every target and its table, as copies.
    controls {
        var out = Dictionary.new;
        controls.keysValuesDo { |key, table| out[key.copy] = table.copy };
        ^out
    }

    // Extra SC Event keys this map may write, sorted.
    //
    // Union across timelines. `PatternWriter` omits keys that are nil
    // throughout. A baseline is not carried until something writes
    // that key.
    //
    // >>> PlaybackControlMap.new.carriedKeys                     -> [ ]
    // >>> PlaybackControlMap.new.panAt(0, 0, -0.5).carriedKeys   -> [ pan ]
    // Named targets count before resolution because the key is already known.
    carriedKeys {
        var names = Set.new;
        controls.do { |table| table.keysDo { |key| names.add(key) } };
        overrides.do { |entry| names.add(entry[\key]) };
        namedControls.do { |entry| names.add(entry[2]) };
        ^names.asArray.collect { |key| key.asString }.sort
            .collect { |name| name.asSymbol }
    }

    // Rebuilt through the public setters, preserving their checks.
    //
    // >>> PlaybackControlMap.new.panAt(0, 0, 0.25).copy.controlsAt(0, 0)[\pan]
    // 0.25
    copy {
        var out = PlaybackControlMap.new;
        controls.keysValuesDo { |key, table|
            table.keysValuesDo { |name, value|
                out.controlAt(key[0], key[1], name, value)
            }
        };
        namedControls.do { |entry|
            out.controlFor(entry[0], entry[1], entry[2], entry[3])
        };
        baselines.keysValuesDo { |key, value| out.baseline(key, value) };
        overrides.do { |entry|
            out.controlAtOffset(entry[\staffIndex], entry[\timelineIndex],
                entry[\offset], entry[\key], entry[\value])
        };
        ^out
    }

    // The events of `element` with this map's keys laid over them.
    //
    // The old `map` slot is refused by name instead of being read as `prepare`.
    events { |element, map, prepare = true|
        var tree;
        map !? { this.prRefusePeer(map) };
        tree = Rastrum.prepared(element, prepare);
        ^this.eventsFrom(Rastrum.events(tree, false))
    }

    // The core, over events somebody already has. Events are copied
    // first, leaving a caller's own alone. Targets absent from the
    // event set are refused.
    //
    // >>> PlaybackControlMap.new.panAt(0, 0, -0.5)
    //     .eventsFrom(Rastrum.events(Measure("1/4", "c4"))).first[\pan]
    // -0.5
    eventsFrom { |events|
        var out = events.collect { |event| event.copy };
        var resolved;

        this.prCheckTargets(out);
        resolved = this.prResolved(out);
        out.do { |event|
            var payload = event[\rastrum];
            var here = [payload[\staffIndex], payload[\timelineIndex]];
            this.prKeysOf(here, resolved).do { |key|
                event[key] = this.prValueOf(payload, here, key, resolved)
            }
        };
        ^out
    }

    // Resolved table for one score, with named targets laid into
    // indexed ones.
    //
    // Report missing names before clashes.
    prResolved { |events|
        var out = Dictionary.new;
        var unresolved = List.new;
        var clashes = List.new;

        if (namedControls.isEmpty) { ^controls };
        controls.keysValuesDo { |key, table| out[key.copy] = table.copy };
        namedControls.do { |entry|
            var hits = this.prTimelinesNamed(events, entry[0], entry[1]);
            var here, table, already;

            if (hits.size != 1) {
                unresolved.add([entry[0], entry[1], hits.size])
            } {
                here = hits.first;
                table = out[here] ?? {
                    var made = IdentityDictionary.new;
                    out[here] = made;
                    made
                };
                already = table[entry[2]];
                if (already.notNil and: { already != entry[3] }) {
                    clashes.add([entry, here, already])
                } {
                    table[entry[2]] = entry[3]
                };
            };
        };
        if (unresolved.notEmpty) { this.prRefuseName(unresolved.first) };
        if (clashes.notEmpty) { this.prRefuseNamedClash(clashes.first) };
        ^out
    }

    // Every timeline both names reach.
    prTimelinesNamed { |events, staffName, voiceName|
        ^events
            .collect { |event| event[\rastrum] }
            .select { |payload|
                payload[\staff] == staffName and: { payload[\voice] == voiceName }
            }
            .collect { |payload| [payload[\staffIndex], payload[\timelineIndex]] }
            .as(Set).asArray
    }

    // Every key one timeline carries: constants plus override keys.
    prKeysOf { |here, resolved|
        var names = Set.new;

        resolved[here] !? { |table| table.keysDo { |key| names.add(key) } };
        overrides.do { |entry|
            if (entry[\staffIndex] == here[0]
                and: { entry[\timelineIndex] == here[1] }) {
                    names.add(entry[\key])
            }
        };
        ^names
    }

    // The override written at this event, then the timeline's
    // constant, then the map's baseline. Nothing is guessed at the
    // end of that list.
    prValueOf { |payload, here, key, resolved|
        var table = resolved[here];
        var found;

        // Inserted ornaments share host offsets; they take the resting value.
        if (payload[\grace] != true) {
            found = overrides.detect { |entry|
                (entry[\key] == key)
                    and: { entry[\staffIndex] == here[0] }
                    and: { entry[\timelineIndex] == here[1] }
                    and: { entry[\offset] == payload[\offset] } };
            found !? { |entry| ^entry[\value] };
        };
        table !? { table[key] !? { |value| ^value } };
        baselines[key] !? { |value| ^value };
        this.prRefuseRestingValue(here, key);
    }

    // Every target must exist here; note-local targets must reach written notes.
    prCheckTargets { |events|
        var found = List.new;
        var present = Set.new;
        var missing;

        events.do { |event, index|
            var payload = this.prPayloadOf(event, index);
            var here = [payload[\staffIndex], payload[\timelineIndex]];
            present.add(here);
            found.add(IdentityDictionary[
                \key -> here, \offset -> payload[\offset],
                \rest -> (payload[\rest] == true),
                \grace -> (payload[\grace] == true)]);
        };
        missing = controls.keys.asArray
            .reject { |key| present.includes(key) }
            .sort { |a, b| a.asString <= b.asString };
        if (missing.notEmpty) { this.prRefuseTarget(missing.first, present) };
        overrides.do { |entry| this.prCheckOverride(entry, found, present) };
    }

    prCheckOverride { |entry, found, present|
        var here = [entry[\staffIndex], entry[\timelineIndex]];
        var at = entry[\offset];
        var atMoment, sounding;

        if (present.includes(here).not) { this.prRefuseTarget(here, present) };
        atMoment = found.select { |each|
            each[\key] == here and: { each[\offset] == at } };
        sounding = atMoment.select { |each|
            each[\grace].not and: { each[\rest].not } };

        if (atMoment.isEmpty) { this.prRefuseOffset(here, at, entry[\key]) };
        if (sounding.isEmpty) {
            if (atMoment.any { |each| each[\rest] }) {
                this.prRefuseRestTarget(here, at, entry[\key])
            };
            this.prRefuseOrnamentTarget(here, at, entry[\key]);
        };
    }

    // One Ppar over the timelines, as `PlaybackMap#pattern` answers one.
    //
    // Numeric score tempo marks are honored as notation. `tempo:
    // false` leaves the clock to a `PlaybackTempoMap`.
    pattern { |element, map, prepare = true, tempo = true|
        var tree, music;
        map !? { this.prRefusePeer(map) };
        tree = Rastrum.prepared(element, prepare);
        music = PatternWriter.pattern(this.events(tree, nil, false),
            this.carriedKeys);
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    // One Pbind per timeline, for a caller who wants them apart.
    pbinds { |element, map, prepare = true|
        map !? { this.prRefusePeer(map) };
        ^PatternWriter.pbinds(this.events(element, nil, prepare),
            this.carriedKeys)
    }

    // Compose layers with `PlaybackProfile`, where order is explicit.
    prRefusePeer { |map|
        Error("PlaybackControlMap: compose playback layers with PlaybackProfile, "
            "not this method. Got %.".format(
                map.class.name)).throw
    }

    // Checked target first, then the table, then the value: nothing is written
    // until every check has passed.
    prSet { |staffIndex, timelineIndex, key, value|
        var target = this.prCheckedTarget(staffIndex, timelineIndex);
        var table = controls[target];

        if (table.isNil) {
            table = IdentityDictionary.new;
            controls[target] = table;
        };
        table[key] = value;
        ^this
    }

    // Append named entries; `prResolved` decides whether they reach one target.
    prSetNamed { |staffName, voiceName, key, value|
        namedControls.add([staffName, voiceName, key, value]);
        ^this
    }

    // An index pair. Name pairs are checked only once a score arrives.
    prCheckedTarget { |staffIndex, timelineIndex|
        [staffIndex, timelineIndex].do { |value, position|
            if (value.isKindOf(Integer).not or: { value < 0 }) {
                Error("PlaybackControlMap: % index must be a non-negative "
                    "Integer, got %.".format(
                        ["staff", "timeline"][position],
                        value.asCompileString)).throw
            }
        };
        ^[staffIndex, timelineIndex]
    }

    // Note [What this map may not write].
    prCheckedKey { |key|
        var name;
        if (key.isNil) {
            Error("PlaybackControlMap: control key cannot be nil.").throw
        };
        name = key.asSymbol;
        if (structuralKeys.includes(name)) {
            Error("PlaybackControlMap: % is a structural event key.".format(name)).throw
        };
        if (payloadKeys.includes(name)) {
            Error("PlaybackControlMap: % is reserved by the \\rastrum payload.".format(name)).throw
        };
        if (interpretedKeys.includes(name)) {
            Error("PlaybackControlMap: % belongs to another playback layer.".format(name)).throw
        };
        ^name
    }

    // Not nil, and not a Pattern: this map writes constants.
    prCheckedValue { |key, value|
        if (value.isNil) {
            Error("PlaybackControlMap: % cannot be nil.".format(key)).throw
        };
        if (value.isKindOf(Pattern)) {
            Error("PlaybackControlMap: % must be one constant per timeline, "
                "got %.".format(key, value.class)).throw
        };
        ^value
    }

    // -1 to 1, the range `Pan2` reads. Refuse instead of clipping.
    prCheckedPan { |value|
        var checked = this.prCheckedValue(\pan, value);
        if (checked.isNumber.not
            or: { checked < -1 } or: { checked > 1 }) {

            Error("PlaybackControlMap: pan must be between -1 and 1, got %."
                .format(value.asCompileString)).throw
        };
        ^checked
    }

    // A whole bus index.
    prCheckedBus { |value, key|
        var checked = this.prCheckedValue(key, value);
        if (checked.isKindOf(Integer).not or: { checked < 0 }) {
            Error("PlaybackControlMap: % must be a non-negative bus index, got %."
                .format(key, value.asCompileString)).throw
        };
        ^checked
    }

    // A Group or node id, matching SC's own `\group`.
    prCheckedGroup { |value|
        var checked = this.prCheckedValue(\group, value);
        if (checked.isKindOf(Node)) { ^checked };
        if (checked.isKindOf(Integer).not or: { checked < 0 }) {
            Error("PlaybackControlMap: group must be a Group or non-negative "
                "node id, got %.".format(value.asCompileString)).throw
        };
        ^checked
    }

    // Require `EventWriter` payloads before writing controls.
    prPayloadOf { |event, index|
        var payload = event !? { event[\rastrum] };
        if (payload.isNil) {
            Error("PlaybackControlMap: event % has no \\rastrum payload. Use "
                "EventWriter."
                .format(index)).throw
        };
        // `\offset` is required only for note-local overrides.
        [\staffIndex, \timelineIndex].asArray
            .addAll(if (overrides.isEmpty) { [] } { [\offset] })
            .do { |key|
                if (payload[key].isNil) {
                    Error("PlaybackControlMap: event % payload has no %. Use "
                        "EventWriter.".format(index, key)).throw
                }
            };
        ^payload
    }

    // Refuse missing resting values.
	// Each control needs its own baseline.
    prRefuseRestingValue { |here, key|
        Error("PlaybackControlMap: staff % timeline % has % on part of it and no "
            "value for the rest. Add baseline(%, value) or controlAt(%, %, %, "
            "value).".format(here[0], here[1], key, key.asCompileString,
                here[0], here[1], key.asCompileString)).throw
    }

    prRefuseOffset { |here, at, key|
        Error("PlaybackControlMap: no event of staff % timeline % begins at %, "
            "so the % written there reaches nothing."
            .format(here[0], here[1], at, key)).throw
    }

    // A rest carries the key like any other uncovered event and isn't
    // a target for one: it makes no synth for a control to reach.
    prRefuseRestTarget { |here, at, key|
        Error("PlaybackControlMap: staff % timeline % rests at %, and the % "
            "written there has no synth to reach.".format(
                here[0], here[1], at, key)).throw
    }

    prRefuseOrnamentTarget { |here, at, key|
        Error("PlaybackControlMap: the only thing staff % timeline % has at % is "
            "an inserted ornament. Target a written note. The % it would carry "
            "is the value in force.".format(here[0], here[1], at, key)).throw
    }

    // Missing or ambiguous named target.
    prRefuseName { |entry|
        var staffName = entry[0].asCompileString;
        var voiceName = entry[1].asCompileString;

        if (entry[2] == 0) {
            Error("PlaybackControlMap: no timeline in this score is staff % "
                "voice %. Target by index with controlAt."
                .format(staffName, voiceName)).throw
        };
        Error("PlaybackControlMap: staff % voice % is % timelines, so it does "
            "not say which to map. Target by index with controlAt."
			.format(staffName, voiceName, entry[2])).throw
    }

    // Two targets, one key, two values.
    prRefuseNamedClash { |clash|
        var entry = clash[0];
        var here = clash[1];

        Error("PlaybackControlMap: staff % voice % is staff % timeline %, which "
            "already has % %. Saying % there as well is two values for one key. "
            "write one of them.".format(
                entry[0].asCompileString, entry[1].asCompileString,
                here[0], here[1], entry[2], clash[2].asCompileString,
                entry[3].asCompileString)).throw
    }

    // The present timelines are stringified before sorting, sclang
    // not sorting Array pairs directly.
    prRefuseTarget { |key, present|
        Error("PlaybackControlMap: this score has no staff % timeline %. It has %."
            .format(
                key[0], key[1],
                present.asArray.collect { |each| each.asString }
                    .sort.join(", "))).throw
    }
}
