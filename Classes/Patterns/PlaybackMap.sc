// Note [Playback interpretation is explicit]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Structural writers leave sound choices out. This optional layer
// writes note-local playback keys. Tempo is separate: it governs a
// moment, not a note. A nil in a `Pbind` key ends the stream. Any key
// this map claims is present on every event of a timeline or absent
// from all of them.

// PlaybackMap: optional note-local interpretation over structural events.
//
// Instruments target timeline indexes or names resolved against a
// score. The map copies events, never moves `dur` or payload
// `offset` and plays nothing.
PlaybackMap {
    // Indexed and named instrument targets stay separate until resolution.
    //
    // `dynamics` nil means loudness interpretation is off.
    var instruments, namedInstruments, dynamics, baselineAmp;
    var articulations, baselineLegato;
    var articulationLoudnesses;
    // Sforzando attack multiplier over the level it names.
    var sforzandoAttack;
    // Nil means hairpin interpretation is off. Dynamics gives ramp
    // endpoints their amplitudes.
    var hairpins;

    // Nil means scalar ramp keys. See Note [Loudness as breakpoints] in PlaybackMapLoudness.sc.
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
        // Five-deep dynamics saturate at the four-deep endpoints. Rescaling
        // would change existing scores; the loud end is SC's ordinary 1.0 ceiling.
        // The cost is a flat hairpin between saturated neighbors. Callers
        // can replace the table.
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

    // Note [Maps expose guarded copies]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Setters check first and mutate last. A refused call leaves the map
    // unchanged. Accessors answer copies, so callers can't bypass the
    // setters' checks.

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

    // Lowers loudness to an amplitude envelope instead of scalar ramp keys.
    // See Note [Loudness as breakpoints] in PlaybackMapLoudness.sc.
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

    // Declared width; nil when loudness lowers to scalar keys.
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
            Error(
                "PlaybackMap: withInstrumentsFrom needs a PlaybackMap, not %."
                .format(other.asCompileString)
            ).throw
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
        Error(
            "PlaybackMap: % is % in this map and % in the overlay, so the two do not "
            "say which instrument plays it. Change one of them."
            .format(target, here, there)
        ).throw
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

    // Non-negative only. Range policy beyond that belongs to the
    // SynthDef.
    prCheckedFactor { |value, name, what|
        if (value.isNumber.not or: { value < 0 }) {
            Error(
                "PlaybackMap: % needs % of zero or more, not %."
                .format(name, what, value.asCompileString)
            ).throw
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
            Error(
                "PlaybackMap: % is not %. Values: %."
                .format(
                    name.asCompileString,
                    what,
                    vocabulary.collect { |each| each.asString }.join(", ")
                )
            ).throw
        };
        ^name
    }
}
