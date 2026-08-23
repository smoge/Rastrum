// Note [Playback interpretation is explicit]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Structural writers leave sound choices out. This optional layer
// writes note-local playback keys. Tempo is separate: it governs a
// moment, not a note. A nil in a `Pbind` key ends the stream. Any key
// this map claims is present on every event of a timeline or absent
// from all of them.

// Note [A hairpin is a ramp, not an attack]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Dynamics and articulations are attack-local. Hairpins and
// continuation dynamics can change one tied event, so they can't be
// only `\amp`. The scalar lowering carries one transition on four
// keys: `\amp`, `\ampEnd`, `\ampRampStart` and `\ampRampDur`. Flat
// events carry a zero ramp. A dynamic inside a tied run is a
// zero-duration transition. A second transition needs Note [Loudness
// as breakpoints]. Hairpins must close on a dynamic, or playback
// would invent a destination. Times are beats, matching `\dur`. These
// keys describe an event. A SynthDef has to read them.

// Note [The table saturates at its ends]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Dynamics past either end sound as their nearest neighbor: `ppppp`
// as `pppp`, `fffff` as `ffff`. Rescaling would change existing
// scores.
//
// At the loud end, `1.0` is SC's ordinary ceiling. The quiet end
// saturates for one rule at both extremes.
//
// Cost: a hairpin between saturated neighbors is flat. The table is
// replaceable.

// Note [Loudness as breakpoints]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Loudness is built as an attack level plus transitions. A step has
// no length. A hairpin does! The scalar lowering writes one
// transition. The envelope lowering writes many as Ref-wrapped
// `ampLevels` and `ampTimes`, padded to the declared SynthDef width
// and refused if the shape needs more. `\amp` remains the attack
// level, and `PlaybackEnvelopeMap` reserves the same keys so two
// layers can't shape one loudness.

// Note [Maps expose guarded copies]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Setters check first and mutate last. A refused call leaves the map
// unchanged. Accessors answer copies, so callers can't bypass the
// setters' checks.


// PlaybackMap: optional note-local interpretation over structural events.
//
// Instruments target timeline indexes or names resolved against a
// score. The map copies events, never moves `dur` or payload
// `offset`, and plays nothing.
PlaybackMap {
    // Indexed and named instrument targets stay separate until resolution.
    //
    // `dynamics` nil means loudness interpretation is off.
    var instruments, namedInstruments, dynamics, baselineAmp;
    var articulations, baselineLegato;
    var articulationLoudnesses;
    // Sforzando attack multiplier over the level it names.
    var sforzandoAttack;
    // nil means hairpin interpretation is off. Dynamics gives ramp
    // endpoints their amplitudes.
    var hairpins;

    // nil means scalar ramp keys, see Note [Loudness as breakpoints].
    // An Integer is the declared segment count of the amplitude
    // envelope.
    var loudnessSegments;

    classvar defaultDynamics;              // Replaceable defaults, handed out as copies.
    classvar <defaultBaselineAmp = 0.1;    // SC's default event amplitude.

    // Duration only. Loud articulations stay at 1.0 here and can be mapped by
    // the loudness table.
    classvar defaultArticulations;

    // Neutral value for events with no articulation after `\legato` is enabled.
    classvar <defaultBaselineLegato = 1.0;

    // Sforzando strikes above its named level and settles onto it.
    classvar <defaultSforzandoAttack = 1.5;

    classvar defaultArticulationLoudness;  // Loudness multipliers for articulations.

    *defaultArticulations { ^defaultArticulations.copy }

    *defaultArticulationLoudness { ^defaultArticulationLoudness.copy }

    *initClass {
        defaultArticulations = IdentityDictionary[
            \staccato  -> 0.5, \staccatissimo -> 0.25,
			\tenuto    -> 1.0,
            \accent    -> 1.0,
			\marcato   -> 1.0,
            // Detached but not short: between staccato and tenuto.
            \portato   -> 0.75,
            // Fermata, breath and caesura need caller policy before
            // they change duration.
            \fermata   -> 1.0,
			\breath    -> 1.0,
			\caesura   -> 1.0
        ];
        defaultArticulationLoudness = IdentityDictionary[
            \staccato -> 1.0, \staccatissimo -> 1.0,
			\tenuto   -> 1.0,
            \portato  -> 1.0,
            \accent   -> 1.25,
			\marcato  -> 1.5,
			\fermata  -> 1.0,
            \breath   -> 1.0,
			\caesura  -> 1.0
        ];
        // Five-deep dynamics saturate at the four-deep endpoints.
        defaultDynamics = IdentityDictionary[
            \ppppp -> 0.03,
            \pppp  -> 0.03,
			\ppp   -> 0.05,
			\pp    -> 0.08,
			\p     -> 0.12,
			\mp    -> 0.2,
            \mf    -> 0.3,
			\f     -> 0.45,
			\ff    -> 0.65,
			\fff   -> 0.85,
			\ffff  -> 1.0,
            \fffff -> 1.0
        ];
    }

    // >>> PlaybackMap.defaultDynamics[\ff]                   -> 0.65
    // >>> PlaybackMap.defaultArticulations[\staccato]        -> 0.5
    // >>> PlaybackMap.defaultArticulationLoudness[\marcato]  -> 1.5
    *defaultDynamics { ^defaultDynamics.copy }

    *new { ^super.new.init }

    init {
        instruments = Dictionary.new;
        namedInstruments = List.new;
        baselineAmp = PlaybackMap.defaultBaselineAmp;
        baselineLegato = PlaybackMap.defaultBaselineLegato;
        sforzandoAttack = PlaybackMap.defaultSforzandoAttack;
        ^this
    }

    // Target an instrument by timeline index. The pair's shape is
    // checked here. Whether a score has that timeline is checked when
    // the map meets one.
    instrumentAt { |staffIndex, timelineIndex, instrument|
        var target = this.prCheckedTarget(staffIndex, timelineIndex);
        var checked = this.prCheckedInstrument(instrument);

        instruments[target] = checked;
        ^this
    }

    // Target an instrument by score names.
    instrumentFor { |staffName, voiceName, instrument|
        namedInstruments.add([staffName, voiceName,
            this.prCheckedInstrument(instrument)]);
        ^this
    }

    // Enables dynamics and fills missing entries from the default
    // table. `baseline` is the level before the first dynamic.
    //
    // >>> PlaybackMap.new.interpretsDynamics                -> false
    // A sforzando reads this table for its named settle level.
    //
    // >>> PlaybackMap.new.useDynamics.dynamics[\ff]         -> 0.65
    // >>> PlaybackMap.new.dynamic(\ff, 0.9).dynamics[\ff]   -> 0.9
    // >>> PlaybackMap.new.dynamic(\ff, 0.9).dynamics[\mf]   -> nil
    useDynamics { |baseline|
        var checked = baseline !? { this.prCheckedAmp(baseline, \baseline) };
        var table = dynamics ?? { IdentityDictionary.new };

        defaultDynamics.keysValuesDo { |name, amp| table[name] = table[name] ? amp };
        dynamics = table;
        checked !? { baselineAmp = checked };
        ^this
    }

    // Enables hairpins and dynamics, a ramp needs both endpoints.
    //
    // >>> PlaybackMap.new.interpretsHairpins                    -> false
    // >>> PlaybackMap.new.useHairpins.interpretsHairpins        -> true
    // >>> PlaybackMap.new.useHairpins.interpretsDynamics        -> true
    // >>> PlaybackMap.new.useHairpins.dynamic(\ff, 0.3).dynamics[\ff]  -> 0.3
    useHairpins { |baseline|
        this.useDynamics(baseline);
        hairpins = true;
        ^this
    }

    interpretsHairpins { ^hairpins == true }

    // Lowers loudness to an amplitude envelope instead of scalar ramp keys, by
    // Note [Loudness as breakpoints].
    //
    // `segments` is the width this map and the SynthDef agree on:
    // `ampLevels` has `segments + 1` channels and `ampTimes` has `segments`.
    //
    // >>> PlaybackMap.new.interpretsLoudnessEnvelopes              -> false
    // >>> PlaybackMap.new.useLoudnessEnvelopes(4).loudnessSegments -> 4
    // >>> PlaybackMap.new.useLoudnessEnvelopes(4).interpretsDynamics -> true
    // >>> PlaybackMap.new.useLoudnessEnvelopes(4).carriedKeys
    // [ amp, ampLevels, ampTimes ]
    useLoudnessEnvelopes { |segments = 4, baseline|
        var checked = this.prCheckedSegments(segments);
        this.useDynamics(baseline);
        loudnessSegments = checked;
        ^this
    }

    interpretsLoudnessEnvelopes { ^loudnessSegments.notNil }

    // The declared width, or nil when loudness lowers to the scalar
    // keys.
    loudnessSegments { ^loudnessSegments }

    // The two keys an amplitude envelope writes.
    //
    // >>> PlaybackMap.envelopeKeys   -> [ ampLevels, ampTimes ]
    *envelopeKeys { ^[\ampLevels, \ampTimes] }

    // Sets the dynamic baseline without enabling dynamics.
    //
    // >>> PlaybackMap.new.baselineAmp               -> 0.1
    // >>> PlaybackMap.new.baseline(0.4).baselineAmp -> 0.4
    baseline { |amp|
        baselineAmp = this.prCheckedAmp(amp, \baseline);
        ^this
    }

    // Replaces one dynamic value and enables dynamics. Other names
    // remain unmapped unless `useDynamics` fills the table.
    dynamic { |name, amp|
        var checkedName = this.prCheckedDynamic(name);
        var checkedAmp = this.prCheckedAmp(amp, name);

        dynamics = dynamics ?? { IdentityDictionary.new };
        dynamics[checkedName] = checkedAmp;
        ^this
    }

    // Enables articulation duration as `\legato`. This map never
    // writes `\sustain`, which would replace the note's own length.
    useArticulations { |baseline|
        var checked = baseline !? { this.prCheckedLegato(baseline, \baseline) };
        var table = articulations ?? { IdentityDictionary.new };

        defaultArticulations.keysValuesDo { |name, value|
            table[name] = table[name] ? value
        };
        articulations = table;
        checked !? { baselineLegato = checked };
        ^this
    }

    articulation { |name, legato|
        var checkedName = this.prCheckedArticulation(name);
        var checkedLegato = this.prCheckedLegato(legato, name);

        articulations = articulations ?? { IdentityDictionary.new };
        articulations[checkedName] = checkedLegato;
        ^this
    }

    // The legato baseline on its own. Inert until articulations are
    // asked for.
    baselineLegato_ { |value|
        baselineLegato = this.prCheckedLegato(value, \baseline);
        ^this
    }

    // Sforzando attack multiplier. Inert until dynamics are on.
    //
    // >>> PlaybackMap.new.sforzandoAttack                        -> 1.5
    // >>> PlaybackMap.new.sforzandoAttack_(2.0).sforzandoAttack  -> 2.0
    sforzandoAttack_ { |factor|
        sforzandoAttack = this.prCheckedLoudness(factor, \sforzando);
        ^this
    }

    sforzandoAttack { ^sforzandoAttack }

    // Enables articulation loudness as `\amp` multipliers.
    //
    // `baseline` is the same amp floor used by dynamics.
    useArticulationLoudness { |baseline|
        var checked = baseline !? { this.prCheckedAmp(baseline, \baseline) };
        var table = articulationLoudnesses ?? { IdentityDictionary.new };

        defaultArticulationLoudness.keysValuesDo { |name, value|
            table[name] = table[name] ? value
        };
        articulationLoudnesses = table;
        checked !? { baselineAmp = checked };
        ^this
    }

    articulationLoudness { |name, factor|
        var checkedName = this.prCheckedArticulation(name);
        var checkedFactor = this.prCheckedLoudness(factor, name);

        articulationLoudnesses = articulationLoudnesses ?? { IdentityDictionary.new };
        articulationLoudnesses[checkedName] = checkedFactor;
        ^this
    }

    interpretsArticulationLoudness { ^articulationLoudnesses.notNil }
    articulationLoudnesses { ^articulationLoudnesses !? { articulationLoudnesses.copy } }
    interpretsArticulations { ^articulations.notNil }
    baselineLegato { ^baselineLegato }
    articulations { ^articulations !? { articulations.copy } }
    interpretsDynamics { ^dynamics.notNil }
    baselineAmp { ^baselineAmp }

    // The chosen indexed mappings, as copies.
    instruments {
        var out = Dictionary.new;
        instruments.keysValuesDo { |key, value| out[key.copy] = value };
        ^out
    }

    namedInstruments { ^namedInstruments.collect { |entry| entry.copy }.asArray }

    dynamics { ^dynamics !? { dynamics.copy } }    // nil means dynamics are off.

    // Rebuilds through public setters, preserving their checks.
    copy {
        var out = PlaybackMap.new;
        instruments.keysValuesDo { |key, value|
            out.instrumentAt(key[0], key[1], value)
        };
        namedInstruments.do { |entry|
            out.instrumentFor(entry[0], entry[1], entry[2])
        };
        // Preserve a partial table rather than filling defaults during copy.
        dynamics !? {
            dynamics.keysValuesDo { |name, amp| out.dynamic(name, amp) };
            out.baseline(baselineAmp);
            out.sforzandoAttack_(sforzandoAttack);
        };
        articulations !? {
            articulations.keysValuesDo { |name, value| out.articulation(name, value) };
            out.baselineLegato_(baselineLegato);
        };
        articulationLoudnesses !? {
            articulationLoudnesses.keysValuesDo { |name, value|
                out.articulationLoudness(name, value)
            };
            out.baseline(baselineAmp);
        };
        hairpins !? { out.useHairpins; out.baseline(baselineAmp) };
        loudnessSegments !? {
            out.useLoudnessEnvelopes(loudnessSegments);
            out.baseline(baselineAmp);
        };
        ^out
    }

    // This map's interpretation with another map's instrument
    // targets. Only instruments cross, same-target clashes are
    // refused.
    //
    // >>> PlaybackMap.new.useDynamics.withInstrumentsFrom(
    // PlaybackMap.new.instrumentAt(0, 0, \sine)).instruments[[0, 0]]  -> sine
    // >>> PlaybackMap.new.withInstrumentsFrom(
    // PlaybackMap.new.useDynamics).interpretsDynamics  -> false
    withInstrumentsFrom { |other|
        var out;

        if (other.isKindOf(PlaybackMap).not) {
            Error("PlaybackMap: withInstrumentsFrom needs a PlaybackMap, not %.".format(other.asCompileString)).throw
        };

        // Check before copying, so refusal leaves both maps unchanged.
        other.instruments.keysValuesDo { |key, value|
            var here = instruments[key];
            if (here.notNil and: { here != value }) {
                this.prRefuseInstrumentClash(
                    "staff % timeline %".format(key[0], key[1]), here, value)
            }
        };
        other.namedInstruments.do { |entry|
            var here = this.prNamedDisagreement(entry[0], entry[1], entry[2]);
            here !? {
                this.prRefuseInstrumentClash(
                    "staff % voice %".format(entry[0].asCompileString,
                        entry[1].asCompileString), here, entry[2])
            }
        };

        out = this.copy;
        other.instruments.keysValuesDo { |key, value|
            out.instrumentAt(key[0], key[1], value)
        };
        other.namedInstruments.do { |entry|
            // The pair already agrees, so skip the duplicate.
            if (this.prNamesPair(entry[0], entry[1]).not) {
                out.instrumentFor(entry[0], entry[1], entry[2])
            }
        };
        ^out
    }

    // Existing instrument for this name pair, when it disagrees.
    prNamedDisagreement { |staffName, voiceName, instrument|
        var found = nil;

        namedInstruments.do { |entry|
            if (found.isNil and: {
                this.prSamePair(entry, staffName, voiceName)
                    and: { entry[2] != instrument } }) {
                        found = entry[2]
            }
        };
        ^found
    }

    prNamesPair { |staffName, voiceName|
        ^namedInstruments.any { |entry|
            this.prSamePair(entry, staffName, voiceName)
        }
    }

    prSamePair { |entry, staffName, voiceName|
        ^entry[0] == staffName and: { entry[1] == voiceName }
    }

    prRefuseInstrumentClash { |target, here, there|
        Error("PlaybackMap: % is % in this map and % in the overlay, so the two "
            "do not say which instrument plays it. Change one of them.".format(target, here, there)).throw
    }

    // Extra SC Event keys this map may write. `\amp` appears once
    // even when two loudness passes touch it.
    //
    // Named instrument mappings count: they may resolve when the map meets a
    // score.
    //
    // >>> PlaybackMap.new.carriedKeys                       -> [ ]
    // >>> PlaybackMap.new.useDynamics.carriedKeys
    // [ amp, ampEnd, ampRampStart, ampRampDur ]
    // >>> PlaybackMap.new.useArticulations.carriedKeys      -> [ legato ]

    carriedKeys {
        var out = [];
        if (instruments.notEmpty or: { namedInstruments.notEmpty }) {
            out = out.add(\instrument)
        };
        if (dynamics.notNil or: { articulationLoudnesses.notNil }) {
            out = out.add(\amp)
        };
        // Steps use dynamics too. Scalar and envelope lowerings are exclusive.
        dynamics !? {
            out = out ++ if (loudnessSegments.isNil) {
                [\ampEnd, \ampRampStart, \ampRampDur]
            } {
                PlaybackMap.envelopeKeys
            }
        };
        articulations !? { out = out.add(\legato) };
        ^out
    }

    // Answers copied events with this map's keys laid over them. The structural
    // payload is shared and read-only.
    events { |element, prepare = true|
        var events = Rastrum.events(element, prepare);
        var table = this.prResolve(events);
        var out = events.collect { |event|
            var payload = event[\rastrum];
            var instrument = table[[payload[\staffIndex], payload[\timelineIndex]]];
            var copy = event.copy;
            instrument !? { copy[\instrument] = instrument };
            copy
        };

        // Spans are read before dynamics, so closing dynamics are ramp targets.
        var spans = hairpins !? { this.prHairpinSpans(out) };

        var steps = IdentityDictionary.new;

        dynamics !? {
            this.prApplyDynamics(out, spans, steps);
            this.prApplyLoudness(out, spans ? Dictionary.new, steps);
        };
        articulations !? { this.prApplyArticulations(out) };
        articulationLoudnesses !? { this.prApplyArticulationLoudness(out) };
        ^out
    }

    // Writes `\amp` on every event. A dynamic persists within its
    // timeline. `steps` records continuation dynamics that become
    // jumps in `prApplyLoudness`.
    prApplyDynamics { |events, spans, steps|
        var current = nil;
        var key = nil;
        var unknown = List.new;
        var later = List.new;
        var inside = List.new;

        events.do { |event|
            var payload = event[\rastrum];
            var here = [payload[\staffIndex], payload[\timelineIndex]];
            var amp, accent, attackDynamic;

            if (here != key) { key = here; current = nil };
            later.clear;
            payload[\markings].do { |record|
                // An attack over a level.
				//
				// See Note [A sforzando is an accent at a level] in
                // Marking.sc. The record is kept, not only the level,
                // since it is also the step the note settles onto.
                //
                // An attack is where the note begins, so one written
                // inside a tied run is refused rather than dropped,
                // as a continuation articulation is.
                if (record[\marking].kind == \sforzando) {
                    if (record[\offset] == payload[\offset]) {
                        accent = record
                    } {
                        inside.add([payload, record])
                    }
                };
                if (record[\marking].kind == \dynamic) {
                    if (record[\offset] == payload[\offset]) {
                        // Two on one attack is the last one written,
                        // as a table would answer it.
                        current = record[\marking].value;
                        attackDynamic = record;
                    } {
                        // Inside the event. A hairpin's target
                        // belongs to that ramp, anything else is a
                        // step of its own, and either takes effect
                        // after this event rather than at its attack.
                        if (this.prClosesHairpin(spans, here,
                            record[\offset]).not) {
                                this.prAddStep(steps, event, record)
                        };
                        later.add(record[\marking].value)
                    }
                }
            };

            // Note [A sforzando is a transient over a level]
            // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            //
            // `sffz` is a forced attack on a note whose level is ff:
            // struck above ff, settling onto ff for the rest of that
            // note. The attack is `sforzandoAttack` over the level's
            // amplitude. How sharp is interpretation, which is what
            // this layer is for, and one multiplier serves all four
            // levels because sharpness is what the family shares
            // rather than what each member says. Settling onto the
            // level is a transition of no length at the attack, the
            // same shape a continuation dynamic takes. A dynamic
            // written beside the sforzando is what it settles onto
            // instead, which is the compound a score prints as
            // `sfz/p`. It is the *note's* level and not the
            // passage's. A sforzando is emphasis on the note it
            // stands over, so it leaves `current` alone and a `sfz`
            // in a p passage is followed by p. A dynamic beside it
            // stays in force the way any written dynamic does, since
            // that is what it is.

            accent !? {
                this.prAddStep(steps, event, accent);
                attackDynamic !? { |record|
                    // The level a note settles onto is heard, so it is checked
                    // here. Every other step target is a level that also becomes
                    // some event's own amplitude, and is checked there.
                    if (dynamics[record[\marking].value].isNil) {
                        unknown.add(record[\marking].value)
                    };
                    // Written last at one offset, so the pair resolves to it.
                    this.prAddStep(steps, event, record)
                };
            };
            amp = case
                { accent.notNil } { dynamics[accent[\marking].value] }
                { current.isNil } { baselineAmp }
                { true } { dynamics[current] };
            if (amp.isNil) {
                unknown.add(accent !? { accent[\marking].value } ?? { current })
            };
            // The sharp attack, over the level the note settles onto.
            accent !? { amp = amp !? { amp * sforzandoAttack } };
            event[\amp] = amp ? baselineAmp;
            later.do { |name|
                current = name;
                if (dynamics[name].isNil) { unknown.add(name) }
            };
        };

        if (inside.notEmpty) { this.prRefuseSforzandoInsideEvent(inside.first) };
        if (unknown.notEmpty) { this.prRefuseUnknownDynamic(unknown.first) };
    }

    // One step per offset. The last dynamic at one offset wins.
    // Different offsets become separate transitions for the lowering
    // to accept or refuse.
    prAddStep { |steps, event, record|
        var already = steps[event] ?? { List.new };
        var here = already.detect { |each| each[0] == record[\offset] };

        here !? { already.remove(here) };
        already.add([record[\offset], record[\marking].value]);
        steps[event] = already;
    }

    prClosesHairpin { |spans, key, offset|
        if (spans.isNil) { ^false };
        ^(spans[key] ? []).any { |span| span[\stop] == offset }
    }

    // Hairpin spans per timeline, deduped by offset and attached object.
    prHairpinSpans { |events|
        var ends = Dictionary.new;
        var points = Dictionary.new;
        var seen = Dictionary.new;
        var out = Dictionary.new;

        events.do { |event|
            var payload = event[\rastrum];
            var key = [payload[\staffIndex], payload[\timelineIndex]];
            // Assigned in two steps: `seen[key] = list` answers the
            // Dictionary rather than the list.
            var here = seen[key];
            if (here.isNil) { here = List.new; seen[key] = here };
            payload[\spanners].do { |record|
                var endpoint = record[\spanner];
                if (endpoint.kind == \hairpin and: {
                    this.prFirstSeen(here, record[\offset], endpoint) }) {
                        ends[key] = (ends[key] ?? { List.new })
                            .add([record[\offset], endpoint])
                }
            };
            payload[\markings].do { |record|
                var marking = record[\marking];
                if (marking.kind == \dynamic and: {
                    this.prFirstSeen(here, record[\offset], marking) }) {
                        points[key] = (points[key] ?? { List.new })
                            .add([record[\offset], marking.value])
                }
            };
        };
        ends.keysValuesDo { |key, list|
            out[key] = this.prSpansOf(key, list.asArray,
                (points[key] ? []).asArray)
        };
        ^out
    }

    // Partly tied chords can report one attachment more than once.
    // The same immutable object at another offset is still a second
    // attachment.
    prFirstSeen { |seen, at, object|
        if (seen.any { |each| each[0] == at and: { each[1] === object } }) {
            ^false
        };
        seen.add([at, object]);
        ^true
    }

    // Pair endpoints by id. Stops sort before starts at one offset,
    // so a close/open handoff is legal. Only one hairpin may be open
    // at a time.
    prSpansOf { |key, ends, points|
        var open = Dictionary.new;
        var spans = List.new;
        var sorted = ends.sort { |a, b|
            if (a[0] == b[0]) {
                (a[1].edge == \stop) or: { b[1].edge != \stop }
            } {
                a[0] < b[0]
            }
        };

        sorted.do { |entry|
            var at = entry[0], endpoint = entry[1];
            if (endpoint.edge == \start) {
                if (open.notEmpty) { this.prRefuseOverlappingHairpins(at) };
                open[endpoint.id] = [at, endpoint.direction];
            } {
                var started = open[endpoint.id];
                if (started.isNil) { this.prRefuseUnpairedHairpin(at, \stop) };
                open.removeAt(endpoint.id);
                if (at <= started[0]) { this.prRefuseEmptyHairpin(at) };
                spans.add(this.prSpanFor(started[0], at, started[1], points));
            }
        };
        open.keysValuesDo { |id, started|
            this.prRefuseUnpairedHairpin(started[0], \start)
        };
        ^spans.asArray
    }

    // Resolves one span's two amplitudes and checks that it goes the
    // way it is drawn.
    prSpanFor { |start, stop, direction, points|
        var from = this.prDynamicAt(points, start);
        // The last written, as two on one attack resolve in `prApplyDynamics`.
        var target = points.select { |each| each[0] == stop }.last;
        var span = IdentityDictionary.new;
        var to;

        if (target.isNil) { this.prRefuseHairpinWithoutTarget(start, stop, direction) };
        points.do { |each|
            if (each[0] > start and: { each[0] < stop }) {
                this.prRefuseDynamicInsideHairpin(each[0], each[1], start, stop)
            }
        };
        to = dynamics[target[1]] ? baselineAmp;
        if (direction == \crescendo and: { to <= from }) {
            this.prRefuseHairpinDirection(start, stop, direction, from, to)
        };
        if (direction == \diminuendo and: { to >= from }) {
            this.prRefuseHairpinDirection(start, stop, direction, from, to)
        };
        span[\start] = start;
        span[\stop] = stop;
        span[\direction] = direction;
        span[\startAmp] = from;
        span[\stopAmp] = to;
        ^span
    }

    // The amplitude in force at an offset: the last dynamic written
    // at or before it, or the baseline where none is.
    prDynamicAt { |points, at|
        var found = nil;
        points.do { |each|
            if (each[0] <= at) {
                if (found.isNil or: { each[0] >= found[0] }) { found = each }
            }
        };
        if (found.isNil) { ^baselineAmp };
        ^dynamics[found[1]] ? baselineAmp
    }

    // Writes one complete loudness shape on every event.
    prApplyLoudness { |events, spans, steps|
        events.do { |event|
            var payload = event[\rastrum];
            var key = [payload[\staffIndex], payload[\timelineIndex]];
            var shape = this.prShapeOf(event, payload, spans[key] ? [],
                steps[event] ? []);

            if (loudnessSegments.isNil) {
                this.prWriteRampKeys(event, payload, shape)
            } {
                this.prWriteAmpEnvelope(event, payload, shape)
            };
        }
    }

    // One event's loudness as an attack level and timed transitions.
    // `\name` belongs only to refusals. Exact here rather than in
    // beats: these are compared against each other, and a ramp
    // becomes interpretation at the point it is written out.
    prShapeOf { |event, payload, spans, steps|
        var from = payload[\offset];
        var to = from + payload[\duration];
        var attack = event[\amp] ? baselineAmp;
        var changes = List.new;

        spans.do { |span|
            var rampFrom, rampTo;
            if (span[\start] < to and: { span[\stop] > from }) {
                rampFrom = if (span[\start] > from) { span[\start] } { from };
                rampTo = if (span[\stop] < to) { span[\stop] } { to };
                // A ramp already under way at the attack decides it.
                // One starting later leaves the dynamic in force
                // standing.
                if (span[\start] <= from) { attack = this.prAmpAt(span, rampFrom) };
                changes.add(IdentityDictionary[
                    \at -> (rampFrom - from), \dur -> (rampTo - rampFrom),
                    \amp -> this.prAmpAt(span, rampTo)]);
            }
        };
        // A step is a transition of no length: hold, then jump where it was written.
        steps.do { |step|
            changes.add(IdentityDictionary[
                \at -> (step[0] - from), \dur -> Duration(0),
                \amp -> (dynamics[step[1]] ? baselineAmp), \name -> step[1]]);
        };
        ^IdentityDictionary[\attack -> attack,
            \changes -> changes.asArray.sort { |a, b| this.prPrecedes(a, b) }]
    }

    // At one offset, a zero-duration step precedes a ramp. Ramping
    // first would reach the hairpin target and then jump back to the
    // dynamic. Overlaps are already refused, so only that pair can
    // share an offset.
    prPrecedes { |a, b|
        if (a[\at] == b[\at]) { ^a[\dur] <= b[\dur] };
        ^a[\at] < b[\at]
    }

    // The default lowering, by Note [A hairpin is a ramp, not an
    // attack]. Four scalar keys and one transition. Beats rather than
    // exact Durations, as `\dur` is: these schedule against it, and a
    // ramp is interpretation rather than written time.
    prWriteRampKeys { |event, payload, shape|
        var changes = shape[\changes];
        var change = changes.first;

        if (changes.size > 1) {
            this.prRefuseSecondTransition(payload, changes[1])
        };
        event[\amp] = shape[\attack];
        if (change.isNil) {
            event[\ampEnd] = shape[\attack];
            event[\ampRampStart] = 0.0;
            event[\ampRampDur] = 0.0;
        } {
            event[\ampEnd] = change[\amp];
            event[\ampRampStart] = this.prBeats(change[\at]);
            event[\ampRampDur] = this.prBeats(change[\dur]);
        };
    }

    // The opt-in lowering: many transitions as one `Env` shape. `\amp` stays the attack level.
    prWriteAmpEnvelope { |event, payload, shape|
        var levels = [shape[\attack]];
        var times = [];
        var at = Duration(0);
        var level = shape[\attack];

        shape[\changes].do { |change|
            // Hold what is in force until the transition begins.
            if (change[\at] > at) {
                times = times.add(this.prBeats(change[\at] - at));
                levels = levels.add(level);
            };
            times = times.add(this.prBeats(change[\dur]));
            levels = levels.add(change[\amp]);
            at = change[\at] + change[\dur];
            level = change[\amp];
        };
        if (times.size > loudnessSegments) {
            this.prRefuseSegments(payload, times.size)
        };
        event[\amp] = shape[\attack];
        event[\ampLevels] = this.prLevelsRef(levels);
        event[\ampTimes] = this.prTimesRef(times);
    }

    // Padded to the declared SynthDef width and Ref-wrapped.
    prLevelsRef { |levels|
        var out = levels.copy;
        while { out.size < (loudnessSegments + 1) } { out = out.add(out.last) };
        ^Ref(out)
    }

    // Zero-length tail segments, which an Env reads as no time at all.
    prTimesRef { |times|
        var out = times.collect { |each| each.asFloat };
        while { out.size < loudnessSegments } { out = out.add(0.0) };
        ^Ref(out)
    }

    // Straight in time between the two ends. A curve would be a policy, and
    // there is no page to check one against yet.
    prAmpAt { |span, at|
        var start = span[\start].asFloat;
        var stop = span[\stop].asFloat;
        var here = at.asFloat;
        if (here <= start) { ^span[\startAmp] };
        if (here >= stop) { ^span[\stopAmp] };
        ^span[\startAmp] + ((span[\stopAmp] - span[\startAmp])
            * ((here - start) / (stop - start)))
    }

    prBeats { |duration| ^duration.asFloat * 4 }

    prRefuseOverlappingHairpins { |at|
        Error("PlaybackMap: a hairpin begins at % while another is still open. "
            "Overlapping hairpins are not supported.".format(at)).throw
    }

    prRefuseUnpairedHairpin { |at, edge|
        Error("PlaybackMap: a hairpin % at % has no matching end.".format(edge, at)).throw
    }

    prRefuseEmptyHairpin { |at|
        Error("PlaybackMap: hairpin at % spans no time.".format(at)).throw
    }

    prRefuseHairpinWithoutTarget { |start, stop, direction|
        Error("PlaybackMap: the % from % to % ends on no dynamic, so it has no "
            "target amplitude. Mark the closing leaf.".format(direction, start, stop)).throw
    }

    prRefuseDynamicInsideHairpin { |at, name, start, stop|
        Error("PlaybackMap: % at % is inside the hairpin from % to %, so the ramp "
            "and dynamic both claim that note. Write it at an endpoint."
            .format(name, at, start, stop)).throw
    }

    prRefuseHairpinDirection { |start, stop, direction, from, to|
        Error("PlaybackMap: the % from % to % runs % to %, the wrong way for the "
            "written hairpin.".format(direction, start, stop, from, to)).throw
    }

    // Writes `\legato` on every event.
    //
    // Articulations are attack-local. Several on one attack answer
    // the shortest legato value, independent of written order.
    prApplyArticulations { |events|
        var inside = List.new;
        var unknown = List.new;

        events.do { |event|
            var payload = event[\rastrum];
            var factors = this.prArticulationValues(payload, articulations,
                inside, unknown);

            event[\legato] = if (factors.isEmpty) {
                baselineLegato
            } {
                factors.minItem
            };
        };

        if (inside.notEmpty) { this.prRefuseArticulationInsideEvent(inside.first) };
        if (unknown.notEmpty) { this.prRefuseUnknownArticulation(unknown.first) };
    }

    // Multiplies `\amp` by what the attack's articulations say about loudness.
    //
    // Multiplies the existing `\amp`, so dynamics and accents
    // compose. Several marks on one attack answer the largest
    // multiplier.
    prApplyArticulationLoudness { |events|
        var inside = List.new;
        var unknown = List.new;

        events.do { |event|
            var payload = event[\rastrum];
            var factors = this.prArticulationValues(payload,
                articulationLoudnesses, inside, unknown);
            var base = event[\amp] ? baselineAmp;

            var factor = if (factors.isEmpty) { 1.0 } { factors.maxItem };

            event[\amp] = base * factor;
            // Scale the whole note, not only its attack.
            event[\ampEnd] !? { event[\ampEnd] = event[\ampEnd] * factor };
            // Scale every envelope level for the same reason.
            event[\ampLevels] !? { |levels|
                event[\ampLevels] = Ref(levels.value * factor)
            };
        };

        if (inside.notEmpty) { this.prRefuseArticulationInsideEvent(inside.first) };
        if (unknown.notEmpty) { this.prRefuseUnknownArticulationLoudness(unknown.first) };
    }

    // The articulations written on this event's attack, looked up in
    // one table. Shared by the duration and loudness passes.
    prArticulationValues { |payload, table, inside, unknown|
        var values = List.new;

        payload[\markings].do { |record|
            if (record[\marking].kind == \articulation) {
                if (record[\offset] == payload[\offset]) {
                    var value = table[record[\marking].value];
                    if (value.isNil) {
                        unknown.add(record[\marking].value)
                    } {
                        values.add(value)
                    };
                } {
                    inside.add([payload, record])
                }
            }
        };
        ^values
    }

    // A continuation leaf is not an attack, and a sforzando is
    // nothing else. A held note cannot be struck again part way
    // through, so this is refused where a continuation dynamic is
    // honoured as a step.
    prRefuseSforzandoInsideEvent { |entry|
        var payload = entry[0];
        var record = entry[1];

        Error("PlaybackMap: % is written % into the tied note at %, so it is "
            "not on the attack. A sforzando is an attack, and a tied note is "
            "struck once. Write it on the attack."
            .format(Marking.sforzandoSpelling(record[\marking].value),
                record[\offset] - payload[\offset], payload[\offset])).throw
    }

    // A continuation-leaf articulation is about release, not attack. This layer
    // doesn't model release shaping yet.
    prRefuseArticulationInsideEvent { |entry|
        var payload = entry[0];
        var record = entry[1];

        Error("PlaybackMap: % is written % into the tied note at %, so it is "
            "not on the attack. Write attack articulations on the attack."
            .format(record[\marking].value,
                record[\offset] - payload[\offset], payload[\offset])).throw
    }

    prRefuseUnknownArticulationLoudness { |name|
        Error("PlaybackMap: this score is marked % and the loudness table has no "
            "multiplier for it. Known articulations: %. Call "
            "useArticulationLoudness, or "
            "articulationLoudness(%, factor)."
            .format(name, articulationLoudnesses.keys.asArray
                .collect { |each| each.asString }.sort.join(", "),
                name.asCompileString)).throw
    }

    // Only reachable from a table built by `articulation` alone.
    prRefuseUnknownArticulation { |name|
        Error("PlaybackMap: this score is marked % and the articulation table has "
            "no legato for it. Known articulations: %. Call useArticulations, or "
            "articulation(%, legato)."
            .format(name, articulations.keys.asArray
                .collect { |each| each.asString }.sort.join(", "),
                name.asCompileString)).throw
    }

    // Scalar ramp keys describe one transition. The envelope lowering carries
    // more, so name it in the refusal.
    prRefuseSecondTransition { |payload, change|
        Error("PlaybackMap: % is written % into the tied note at %, which "
            "already changes once. One held note can carry one internal dynamic "
            "step. Call useLoudnessEnvelopes for more."
            .format(change[\name] ?? { "a hairpin" }, change[\at],
                payload[\offset])).throw
    }

    // A wider shape can't be sent without truncating a played level.
    prRefuseSegments { |payload, wanted|
        Error("PlaybackMap: the note at % changes loudness over % envelope "
            "segments and this map was asked for %. Call "
            "useLoudnessEnvelopes(%), and declare ampLevels with % channels."
            .format(payload[\offset], wanted, loudnessSegments, wanted,
                wanted + 1)).throw
    }

    prCheckedSegments { |segments|
        if (segments.isKindOf(Integer).not or: { segments < 1 }) {
            Error("PlaybackMap: useLoudnessEnvelopes needs a segment count of "
                "one or more, not %.".format(segments.asCompileString)).throw
        };
        ^segments
    }

    // Only reachable from a table built by `dynamic` alone:
    // `useDynamics` installs all ten names, and `Marking.dynamic`
    // admits no eleventh.
    prRefuseUnknownDynamic { |name|
        Error("PlaybackMap: this score is marked % and the dynamics table has no "
            "amplitude for it. Known dynamics: %. Call useDynamics, or "
            "dynamic(%, amp)."
            .format(name, dynamics.keys.asArray.collect { |each| each.asString }
                .sort.join(", "), name.asCompileString)).throw
    }

    // Non-negative only. Range policy beyond that belongs to the
    // SynthDef.
    prCheckedFactor { |value, name, what|
        if (value.isNumber.not or: { value < 0 }) {
            Error("PlaybackMap: % needs % of zero or more, not %.".format(
                name, what, value.asCompileString)).throw
        };
        ^value
    }

    prCheckedAmp { |amp, name| ^this.prCheckedFactor(amp, name, "an amplitude") }

    prCheckedLegato { |legato, name|
        ^this.prCheckedFactor(legato, name, "a legato multiplier")
    }

    prCheckedLoudness { |factor, name|
        ^this.prCheckedFactor(factor, name, "a loudness multiplier")
    }

    // Reuse the model's closed vocabularies.
    prCheckedDynamic { |name|
        ^this.prCheckedName(name, Marking.dynamics, "a dynamic")
    }

    prCheckedArticulation { |name|
        ^this.prCheckedName(name, Marking.articulations, "an articulation")
    }

    prCheckedName { |name, vocabulary, what|
        if (vocabulary.includes(name).not) {
            Error("PlaybackMap: % is not %. Values: %.".format(
                name.asCompileString, what,
                vocabulary.collect { |each| each.asString }.join(", "))).throw
        };
        ^name
    }

    // Answers one Ppar over the mapped timelines. Numeric score tempo
    // marks are honored as notation. `tempo: false` leaves the clock
    // to a `PlaybackTempoMap`.
    pattern { |element, prepare = true, tempo = true|
        var tree = Rastrum.prepared(element, prepare);
        var music = PatternWriter.pattern(
            this.events(tree, false), this.carriedKeys);
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    // One Pbind per timeline, for a caller who wants the timelines
    // apart.
    pbinds { |element, prepare = true|
        ^PatternWriter.pbinds(this.events(element, prepare), this.carriedKeys)
    }

    // Answers `[staffIndex, timelineIndex] -> Symbol` for one score.
    // Names resolve first. Exact indexed targets override them.
    // Gather failures, then throw once after both passes.
    prResolve { |events|
        var table = Dictionary.new;
        var missing = List.new;
        var unresolved = List.new;
        var present = events
            .collect { |event|
                [event[\rastrum][\staffIndex], event[\rastrum][\timelineIndex]]
            }
            .as(Set);

        namedInstruments.do { |entry|
            var hits = this.prTimelinesNamed(events, entry[0], entry[1]);
            if (hits.size == 1) {
                table[hits.first] = entry[2]
            } {
                unresolved.add([entry[0], entry[1], hits.size])
            };
        };
        instruments.keysValuesDo { |key, value|
            if (present.includes(key)) { table[key] = value } { missing.add(key) }
        };

        if (unresolved.notEmpty) { this.prRefuseName(unresolved.first) };
        if (missing.notEmpty) { this.prRefuseIndex(missing.first, present) };
        ^table
    }

    // Every timeline that both names reach.
    prTimelinesNamed { |events, staffName, voiceName|
        ^events
            .collect { |event| event[\rastrum] }
            .select { |payload|
                payload[\staff] == staffName and: { payload[\voice] == voiceName }
            }
            .collect { |payload| [payload[\staffIndex], payload[\timelineIndex]] }
            .as(Set).asArray
    }

    // Refuse missing or ambiguous names rather than guessing.
    prRefuseName { |entry|
        var staffName = entry[0].asCompileString;
        var voiceName = entry[1].asCompileString;

        if (entry[2] == 0) {
            Error("PlaybackMap: no timeline in this score is staff % voice %. "
                "Target by index with instrumentAt."
                .format(staffName, voiceName)).throw
        };
        Error("PlaybackMap: staff % voice % is % timelines, so it does not say "
            "which to map. Target by index with instrumentAt.".format(
                staffName, voiceName, entry[2])).throw
    }

    // sclang can't sort Array pairs directly.
    prRefuseIndex { |key, present|
        Error("PlaybackMap: this score has no staff % timeline %. It has %."
            .format(key[0], key[1],
                present.asArray.collect { |each| each.asString }
                    .sort.join(", "))).throw
    }

    // A SynthDef name is a Symbol here. Server availability is session state.
    prCheckedInstrument { |instrument|
        if (instrument.isNil) { Error("PlaybackMap: instrument cannot be nil.").throw };
        ^instrument.asSymbol
    }

    // `PlaybackControlMap.prCheckedTarget`'s rule, in the map that
    // owns instruments: shape at the setter, score presence at
    // `prResolve`. A pair that isn't two non-negative Integers can
    // name no timeline of any score, so nothing is learned by holding
    // it until one arrives.
    prCheckedTarget { |staffIndex, timelineIndex|
        [staffIndex, timelineIndex].do { |value, position|
            if (value.isKindOf(Integer).not or: { value < 0 }) {
                Error("PlaybackMap: % index must be a non-negative Integer, "
                    "got %.".format(["staff", "timeline"][position],
                        value.asCompileString)).throw
            }
        };
        ^[staffIndex, timelineIndex]
    }
}
