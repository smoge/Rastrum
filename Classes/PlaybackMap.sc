// Note [The all or nothing rule]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A nil in a `Pbind` key ends the stream at that step, so an interpreted key
// must be on every event of a timeline or on none of them. Code that writes a
// pattern key refers back here.
//
// Instruments cannot break it: mapping by timeline means every event of a
// timeline gets the same answer. Dynamics and loudness could, so they write
// their key on rests and before the first mark. A baseline covers places where
// the score said nothing. A map that names no timeline adds nothing, and
// `PatternWriter` leaves the key out.


// Note [Structure is derived, interpretation is chosen]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Everything below this layer is structure. `EventWriter` says what sounds and
// when, `PatternWriter` says which stream each event belongs to, and neither
// has an opinion about loudness, timbre or speed.
//
// A decision still has to be made somewhere. A SynthDef has to be named and no
// score names one. "ff" is a word where `\\amp` is a number. "Allegro" is a
// word where a clock takes beats per second. This layer therefore uses optional
// name tables, and neither table is consulted unless you build one.
//
// The rule behind that is the writers' own: the model admits no concept only
// one backend can express. Playback is a backend whose notation is a SynthDef
// somebody else wrote, so its vocabulary cannot live in the tree.
//
// `PlaybackMap` interprets what belongs to a note. `PlaybackTempoMap`
// interprets what belongs to a moment, which is why it is a class of its own
// rather than more keys here.


// Note [A table is handed out as a copy]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Every accessor that answers a table answers a copy, keys included. A table
// handed out is a table somebody can write to, and a write that went around the
// setter could carry a nil, a zero, or an index this map rejects.
// `PlaybackTempoMap` does the same.


// Note [A refused call changes nothing]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Every setter here checks its arguments into locals before touching a field. A
// map is built up over several calls, so a caller who catches an error and
// carries on still holds the map. A partly applied call must not leave an
// interpretation behind.
//
// `dynamic(\loud, 0.5)` is the shape to keep in mind: the name is refused, and
// turning dynamics on is a side effect it must not have had on its way out.


// PlaybackMap: the first layer that is allowed to decide how a score sounds.
//
// See Note [Structure is derived, interpretation is chosen] for why this layer
// exists and why it is optional.
//
// It targets `[staffIndex, timelineIndex]`. See
// Note [A timeline is named by index, not by name] in EventWriter.sc.
// `instrumentFor` accepts names as a convenience and resolves them against the
// score, refusing anything that resolves to nothing or to more than one
// timeline.
//
// Nothing is played and nothing is moved. `pattern` answers a pattern, as
// `Rastrum.pattern` does. The events it works from are copied rather than
// written to, and no interpretation touches `dur` or the payload's `offset`,
// because those are the music rather than a performance of it.
PlaybackMap {
    // `[staffIndex, timelineIndex] -> Symbol`, and the named requests waiting
    // to be resolved into that. Two tables rather than one because a name can
    // only be read against a particular score, and a map is built before it
    // meets one.
    //
    // Not readable directly. Handing out the Dictionary would hand out a way
    // past `instrumentAt`, which is where a nil instrument and an index the
    // score does not have are refused. It would also make the storage shape part
    // of the API. `instruments` and `namedInstruments` answer copies for
    // inspection.
    //
    // `dynamics` is nil until asked for, and that nil is the switch: a map that
    // was never told to interpret loudness writes no `\amp` at all.
    var instruments, namedInstruments, dynamics, baselineAmp;
    var articulations, baselineLegato;
    var articulationLoudnesses;

    // A starting point, and *a* mapping rather than *the* loudness of those
    // words. The right curve depends on the SynthDef, which is why every entry
    // is replaceable and the whole table is optional.
    //
    // Private, and answered as a copy by the class method below. A live table
    // on a classvar is one `~table[\ff] = -3` away from being wrong for every
    // map in the session, including maps built before the write, and it would
    // carry a value `dynamic` would have refused.
    classvar defaultDynamics;

    // SuperCollider's own answer, so an unmarked passage sounds exactly as it
    // would with no dynamics asked for at all: the default event says `amp:
    // ~db.dbamp` with `db: -20.0`, which is 0.1. Declining to change anything
    // is not the same as claiming the music is mezzo-forte.
    classvar <defaultBaselineAmp = 0.1;

    // Duration only, and only where duration is what the articulation is about.
    // A staccato note is a note that sounds short. An accent is a note that is
    // louder, and this table says nothing about loudness, so accent and
    // marcato sit at 1.0 rather than being given an invented shortening.
    // Overridable like any other entry, which is the honest way to leave a
    // decision unmade.
    classvar defaultArticulations;

    // What an unmarked event takes, and what SC's default event answers on its
    // own: `legato: 0.8` is the parent's value, but a Pbind that sets the key
    // must set it everywhere, and 1.0 is the value that changes nothing about
    // the notated length.
    classvar <defaultBaselineLegato = 1.0;

    // The other half of an accent, and a separate table because it is a
    // separate decision. Marcato is the stronger of the two, which is the only
    // ordering between them anyone agrees on. The three that are about length
    // sit at 1.0 here exactly as accent and marcato sit at 1.0 in the length
    // table.
    classvar defaultArticulationLoudness;

    *defaultArticulations { ^defaultArticulations.copy }

    *defaultArticulationLoudness { ^defaultArticulationLoudness.copy }

    *initClass {
        defaultArticulations = IdentityDictionary[
            \staccato -> 0.5, \staccatissimo -> 0.25, \tenuto -> 1.0,
            \accent -> 1.0, \marcato -> 1.0
        ];
        defaultArticulationLoudness = IdentityDictionary[
            \staccato -> 1.0, \staccatissimo -> 1.0, \tenuto -> 1.0,
            \accent -> 1.25, \marcato -> 1.5
        ];
        defaultDynamics = IdentityDictionary[
            \pppp -> 0.03, \ppp -> 0.05, \pp -> 0.08, \p -> 0.12, \mp -> 0.2,
            \mf -> 0.3, \f -> 0.45, \ff -> 0.65, \fff -> 0.85, \ffff -> 1.0
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
        ^this
    }

    // Answers this, so calls chain. A map is a mutable table being built up
    // rather than a value being derived, which is what `copy` is for.
    instrumentAt { |staffIndex, timelineIndex, instrument|
        instruments[[staffIndex, timelineIndex]] = this.prCheckedInstrument(instrument);
        ^this
    }

    // The friendly form, resolved when the map meets a score. Nothing is
    // checked here beyond the instrument itself: whether `"Violin"` has an
    // `"upper"` voice is a question about a score, and this object has not been
    // shown one.
    instrumentFor { |staffName, voiceName, instrument|
        namedInstruments.add([staffName, voiceName,
            this.prCheckedInstrument(instrument)]);
        ^this
    }

    // Turns loudness interpretation on and installs the default table. Nothing
    // happens without this: a `ff` in a score is a Symbol, and what it should
    // do to an amplitude is a decision, so it is asked for by name.
    //
    // `baseline` is what an event before the first dynamic of its timeline
    // gets. It has to be *something*, by Note [The all or nothing rule], and
    // SC's own default is the honest choice: it changes nothing that was not
    // marked.
    //
    // See Note [A refused call changes nothing].
    //
    // Interpretation is off until it is asked for, and asking for one entry
    // asks for the table, which then holds only what was named.
    //
    // >>> PlaybackMap.new.interpretsDynamics                -> false
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

    // The baseline on its own, for a caller who wants the default table and a
    // different floor. Inert until dynamics are asked for, since it only says
    // what an unmarked stretch of an interpreted timeline gets.
    //
    // >>> PlaybackMap.new.baselineAmp               -> 0.1
    // >>> PlaybackMap.new.baseline(0.4).baselineAmp -> 0.4
    baseline { |amp|
        baselineAmp = this.prCheckedAmp(amp, \baseline);
        ^this
    }

    // One entry, replaced. Turns interpretation on too, because overriding an
    // entry of a table you have not asked for would do nothing, and doing
    // nothing quietly is the failure this class exists to avoid.
    //
    // Called without `useDynamics` this leaves a table holding only what was
    // named, and any other dynamic in the score is then refused rather than
    // guessed at. That is the loud version of a half-built table.
    //
    // See Note [A refused call changes nothing].
    dynamic { |name, amp|
        var checkedName = this.prCheckedDynamic(name);
        var checkedAmp = this.prCheckedAmp(amp, name);

        dynamics = dynamics ?? { IdentityDictionary.new };
        dynamics[checkedName] = checkedAmp;
        ^this
    }

    // Articulations, on the same terms as dynamics and with one deliberate
    // narrowing: `\legato` only.
    //
    // SC's default event derives `sustain: ~dur * ~legato * ~stretch`, so an
    // explicit `\sustain` takes over completely and `\legato` stops meaning
    // anything. A map that could write both would be a map with a dead key in
    // it half the time, and which half would depend on the order two tables
    // were filled in. One of them, chosen once: the proportional one, because
    // it follows the note's own length rather than replacing it.
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

    // The legato baseline on its own. Inert until articulations are asked for.
    baselineLegato_ { |value|
        baselineLegato = this.prCheckedLegato(value, \baseline);
        ^this
    }

    // The loudness half of an accent, opt-in on its own terms.
    //
    // Separate from `useArticulations` rather than folded into it, because they
    // are separate decisions about the same five words: one says how long a
    // note sounds and the other how loud, and a score that wants a short accent
    // without a loud one is an ordinary thing to want. Turning either on does
    // not quietly turn on the other.
    //
    // The value is a *multiplier* over whatever `\amp` already says, not an
    // amplitude. An accent is louder than its surroundings, and its
    // surroundings are whatever the dynamics table decided, or the baseline,
    // when dynamics are off. An absolute value here would make an accented `pp`
    // and an accented `ff` the same loudness, which is not what either mark
    // means.
    //
    // `baseline` is that same floor `useDynamics` and `baseline` set, not a
    // second one: there is one `\amp` to multiply, so there is one baseline
    // under it. Passing one here after `useDynamics` moves both.
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

    articulationLoudnesses {
        ^articulationLoudnesses !? { articulationLoudnesses.copy }
    }

    interpretsArticulations { ^articulations.notNil }

    baselineLegato { ^baselineLegato }

    articulations { ^articulations !? { articulations.copy } }

    interpretsDynamics { ^dynamics.notNil }

    baselineAmp { ^baselineAmp }

    // What was chosen, to look at. See Note [A table is handed out as a copy].
    instruments {
        var out = Dictionary.new;
        instruments.keysValuesDo { |key, value| out[key.copy] = value };
        ^out
    }

    namedInstruments { ^namedInstruments.collect { |entry| entry.copy }.asArray }

    // nil when loudness is not interpreted, which is the same question
    // `interpretsDynamics` answers and the same nil `carriedKeys` reads.
    dynamics { ^dynamics !? { dynamics.copy } }

    // Rebuilt through the same two methods rather than by copying the tables
    // across, so a copy cannot hold anything a freshly built map would refuse.
    copy {
        var out = PlaybackMap.new;
        instruments.keysValuesDo { |key, value|
            out.instrumentAt(key[0], key[1], value)
        };
        namedInstruments.do { |entry|
            out.instrumentFor(entry[0], entry[1], entry[2])
        };
        // Entry by entry rather than through `useDynamics`, so a half-built
        // table copies as the half-built table it is rather than quietly
        // gaining the eight defaults it was never given.
        dynamics !? {
            dynamics.keysValuesDo { |name, amp| out.dynamic(name, amp) };
            out.baseline(baselineAmp);
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
        ^out
    }

    // The SC Event keys this map may write. `PatternWriter` carries only the
    // structural keys unless it is told otherwise, so a layer has to say what
    // it adds. The alternative is a globally mutable list of keys, which would
    // make one map's decision visible to every other caller in the session.
    // `\amp` once, however many layers want it: dynamics chooses the value and
    // the loudness layer multiplies it, and a key named twice in a Pbind is a
    // key whose second stream is thrown away.
    //
    // Every key is named only when the layer that writes it is on,
    // `\instrument` included: a map with no instrument mapped writes no
    // `\instrument`, so naming one would say this map may write a key it has no
    // way to produce. A named mapping counts, even though it resolves against
    // the score later and may match nothing, "may write" is what this answers,
    // and it may.
    //
    // >>> PlaybackMap.new.carriedKeys                       -> [ ]
    // >>> PlaybackMap.new.useDynamics.carriedKeys           -> [ amp ]
    // >>> PlaybackMap.new.useArticulations.carriedKeys      -> [ legato ]
    carriedKeys {
        var out = [];
        if (instruments.notEmpty or: { namedInstruments.notEmpty }) {
            out = out.add(\instrument)
        };
        if (dynamics.notNil or: { articulationLoudnesses.notNil }) {
            out = out.add(\amp)
        };
        articulations !? { out = out.add(\legato) };
        ^out
    }

    // Returns the score's events with this map's keys laid over them.
    //
    // Copies rather than writes: `Rastrum.events` answers events a caller may
    // already be holding, and an interpretation that edited them in place would
    // reach backwards into somebody else's values. The `\rastrum` payload is
    // shared with the original, which is safe because it is read-only.
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

        dynamics !? { this.prApplyDynamics(out) };
        articulations !? { this.prApplyArticulations(out) };
        articulationLoudnesses !? { this.prApplyArticulationLoudness(out) };
        ^out
    }

    // Writes `\amp` on every event, walking each timeline in order.
    //
    // A dynamic persists until something changes it, so this is a walk with
    // state rather than a lookup per event, and the state belongs to one
    // timeline. `EventWriter` already hands the events over grouped by timeline
    // and in order within each, so the walk only has to notice the seam. The
    // dynamic does not carry across it, because one voice's `ff` says nothing
    // about the voice beside it.
    //
    // Every event gets a value, including rests and the ones before the first
    // dynamic. That is Note [The all or nothing rule], and it is what makes the
    // interpreted key safe to carry at all.
    prApplyDynamics { |events|
        var current = nil;
        var key = nil;
        var inside = List.new;
        var unknown = List.new;

        events.do { |event|
            var payload = event[\rastrum];
            var here = [payload[\staffIndex], payload[\timelineIndex]];
            var amp;

            if (here != key) { key = here; current = nil };
            payload[\markings].do { |record|
                if (record[\marking].kind == \dynamic) {
                    if (record[\offset] == payload[\offset]) {
                        // Two on one attack is the last one written, as a table
                        // would answer it.
                        current = record[\marking].value
                    } {
                        inside.add([payload, record])
                    }
                }
            };
            amp = if (current.isNil) { baselineAmp } { dynamics[current] };
            if (amp.isNil) { unknown.add(current) };
            event[\amp] = amp ? baselineAmp;
        };

        if (inside.notEmpty) { this.prRefuseDynamicInsideEvent(inside.first) };
        if (unknown.notEmpty) { this.prRefuseUnknownDynamic(unknown.first) };
    }

    // Writes `\legato` on every event.
    //
    // No state, unlike dynamics: an articulation belongs to the note it is
    // written on and says nothing about the next one, so this is a lookup per
    // event rather than a walk. Which also means there is nothing for a
    // timeline seam to reset.
    //
    // Several articulations on one attack are ordinary, such as staccato and
    // accent together. They answer the *shortest* value rather than the last one
    // written. A set of
    // articulations has no order, so reading one would make `.accent.staccato`
    // and `.staccato.accent` different music. The shortest thing said about a
    // note is the one a player would honor.
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
    // Over whatever `\amp` already holds: the dynamics pass has run by now if
    // it is going to, so an accented `pp` is louder than a plain `pp` and
    // quieter than a plain `ff`, which is what both marks mean. With dynamics
    // off there is nothing to multiply but the baseline, and that is what is
    // used.
    //
    // Written on every event, accented or not, by
    // Note [The all or nothing rule], a layer writing `\amp` only on the
    // accents would truncate the music at the first note that was not.
    //
    // Several on one attack answer the *largest*, on the reasoning the length
    // pass gives for answering the shortest.
    prApplyArticulationLoudness { |events|
        var inside = List.new;
        var unknown = List.new;

        events.do { |event|
            var payload = event[\rastrum];
            var factors = this.prArticulationValues(payload,
                articulationLoudnesses, inside, unknown);
            var base = event[\amp] ? baselineAmp;

            event[\amp] = if (factors.isEmpty) { base } { base * factors.maxItem };
        };

        if (inside.notEmpty) { this.prRefuseArticulationInsideEvent(inside.first) };
        if (unknown.notEmpty) { this.prRefuseUnknownArticulationLoudness(unknown.first) };
    }

    // The articulations written *on this event's attack*, looked up in one
    // table.
    //
    // Shared by both articulation passes so they cannot come to different
    // conclusions about the same mark: one written inside a tied run is
    // collected for refusal whichever layer is on, and a mark the table has no
    // value for is collected the same way.
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

    // An articulation on a continuation leaf is about the *release* of a note
    // already sounding, not about an attack. A staccato on the last head of a
    // tie shortens where the note stops. This slice reads attacks, so it has
    // nothing to say about that yet, and says so rather than reading the mark
    // as though it had been written on the attack.
    prRefuseArticulationInsideEvent { |entry|
        var payload = entry[0];
        var record = entry[1];

        Error("PlaybackMap: % is written % into a tied note that begins at % and "
            "lasts %, so it is about the release rather than the attack. This "
            "reads attacks only. Write the articulation on the attack, or leave "
            "articulations off for this score.".format(
                record[\marking].value,
                record[\offset] - payload[\offset],
                payload[\offset], payload[\duration])).throw
    }

    prRefuseUnknownArticulationLoudness { |name|
        Error("PlaybackMap: this score is marked % and the articulation loudness "
            "table has no multiplier for it. It has %. Call "
            "useArticulationLoudness to install the default table, or give this "
            "one a value with articulationLoudness(%, factor)."
            .format(name, articulationLoudnesses.keys.asArray
                .collect { |each| each.asString }.sort.join(", "),
                name.asCompileString)).throw
    }

    // Only reachable from a table built by `articulation` alone.
    prRefuseUnknownArticulation { |name|
        Error("PlaybackMap: this score is marked % and the articulation table has "
            "no legato for it. It has %. Call useArticulations to install the "
            "default table, or give this one a value with articulation(%, legato)."
            .format(name, articulations.keys.asArray
                .collect { |each| each.asString }.sort.join(", "),
                name.asCompileString)).throw
    }

    // A tie is not a re-attack, so a run of tied notes is one event, and a
    // dynamic written on a continuation leaf is a change *during* that event.
    // `\amp` is one number for one synth, so there is nothing honest to write:
    // putting the later dynamic on the attack would start the note at a
    // loudness it never had, and ignoring it would drop something the score
    // says.
    //
    // Refused rather than either. Getting louder through a held note wants
    // automation or a split, and both are decisions this slice has not made.
    prRefuseDynamicInsideEvent { |entry|
        var payload = entry[0];
        var record = entry[1];

        Error("PlaybackMap: % is written % into a tied note that begins at % and "
            "lasts %, so it changes the loudness of a note already sounding. One "
            "`\\amp` cannot say that. Write the dynamic on an attack, or leave "
            "dynamics off for this score.".format(
                record[\marking].value,
                record[\offset] - payload[\offset],
                payload[\offset], payload[\duration])).throw
    }

    // Only reachable from a table built by `dynamic` alone: `useDynamics`
    // installs all ten names, and `Marking.dynamic` admits no eleventh.
    prRefuseUnknownDynamic { |name|
        Error("PlaybackMap: this score is marked % and the dynamics table has no "
            "amplitude for it. It has %. Call useDynamics to install the default "
            "table, or give this one an amplitude with dynamic(%, amp)."
            .format(name, dynamics.keys.asArray.collect { |each| each.asString }
                .sort.join(", "), name.asCompileString)).throw
    }

    // Both tables hold a non-negative multiplier. Whether an amplitude clips or
    // a legato of 3 overlaps the next note is the SynthDef's business. A nil or
    // a negative one is not a value at all, and a nil would end a Pbind's
    // stream at that step.
    prCheckedFactor { |value, name, what|
        if (value.isNumber.not or: { value < 0 }) {
            Error("PlaybackMap: % needs % of zero or more, not %. A nil in a Pbind "
                "key ends the stream at that step.".format(
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

    // The same closed vocabularies the model admits, read from `Marking` rather
    // than repeated here. A second list is a second thing to keep in step.
    prCheckedDynamic { |name|
        ^this.prCheckedName(name, Marking.dynamics, "a dynamic")
    }

    prCheckedArticulation { |name|
        ^this.prCheckedName(name, Marking.articulations, "an articulation")
    }

    prCheckedName { |name, vocabulary, what|
        if (vocabulary.includes(name).not) {
            Error("PlaybackMap: % is not %. The % are %.".format(
                name.asCompileString, what, vocabulary.size,
                vocabulary.collect { |each| each.asString }.join(", "))).throw
        };
        ^name
    }

    // Returns the whole score as one pattern: a Ppar over its timelines, with
    // this map's keys in the Pbinds. Answers it rather than playing it, so it
    // can be inspected, laid over further, or thrown away.
    //
    // The score's own metronome marks come with it, on the same terms
    // `Rastrum.pattern` sets them: a mark carries a number and needs no
    // interpretation, so honoring it is not this map's business and does not
    // wait on it. Prose is passed over. `tempo: false` for a score played
    // through a `PlaybackTempoMap`, so the two do not both set the clock.
    pattern { |element, prepare = true, tempo = true|
        var tree = Rastrum.prepared(element, prepare);
        var music = PatternWriter.pattern(
            this.events(tree, false), this.carriedKeys);
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    // One Pbind per timeline, for a caller who wants the timelines apart.
    pbinds { |element, prepare = true|
        ^PatternWriter.pbinds(this.events(element, prepare), this.carriedKeys)
    }

    // Returns `[staffIndex, timelineIndex] -> Symbol` for one score.
    //
    // Named requests resolve first and indexed ones are written over them,
    // since an index says exactly which timeline is meant and a name says which
    // one it hopes is meant. Setting both is how "this instrument for the part,
    // except that voice" is written.
    //
    // Everything wrong is gathered and refused *after* both walks rather than
    // where it is found. A throw out of an iteration here unwound badly: a
    // caught error left the calling frame holding the wrong `this`. And the
    // shape is better anyway: check, finish, then complain once.
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

    // Every timeline of this score that a staff name and a voice name reach. A
    // query and nothing else: what to do about none of them, or several, is
    // decided where the walk has finished.
    prTimelinesNamed { |events, staffName, voiceName|
        ^events
            .collect { |event| event[\rastrum] }
            .select { |payload|
                payload[\staff] == staffName and: { payload[\voice] == voiceName }
            }
            .collect { |payload| [payload[\staffIndex], payload[\timelineIndex]] }
            .as(Set).asArray
    }

    // Refuses rather than guesses. A name that matches nothing is a typo or a
    // score that changed, and a name that matches two timelines is the exact
    // confusion indexes exist to prevent, silently taking the first would send
    // one staff's music to another staff's instrument.
    prRefuseName { |entry|
        var staffName = entry[0].asCompileString;
        var voiceName = entry[1].asCompileString;

        if (entry[2] == 0) {
            Error("PlaybackMap: no timeline in this score is staff % voice %. "
                "Names are optional, so check the score names them, or target "
                "the timeline by index with instrumentAt.".format(
                    staffName, voiceName)).throw
        };
        Error("PlaybackMap: staff % voice % is % timelines in this score, so it "
            "does not say which one to map. Target them by index with "
            "instrumentAt - a name can repeat, an index cannot.".format(
                staffName, voiceName, entry[2])).throw
    }

    // An index naming no timeline is a typo or a score that moved on, and a map
    // that quietly did nothing is the failure this class exists to make
    // visible.
    //
    // The timelines it does have are listed as strings rather than sorted as
    // pairs: sclang compares two Arrays element by element and answers an Array
    // of Booleans, which `sort` cannot branch on.
    prRefuseIndex { |key, present|
        Error("PlaybackMap: this score has no staff % timeline %. It has %. An "
            "instrument named for a timeline that is not there would silently do "
            "nothing.".format(key[0], key[1],
                present.asArray.collect { |each| each.asString }
                    .sort.join(", "))).throw
    }

    // A SynthDef name is a Symbol here. Whether the server has one by that name
    // is the session's business and not this object's, but a nil would reach a
    // Pbind and end the stream, so it is refused where it was written rather
    // than several steps later.
    prCheckedInstrument { |instrument|
        if (instrument.isNil) {
            Error("PlaybackMap: an instrument cannot be nil. A nil in a Pbind key "
                "ends the stream at that step, so this would quietly stop the "
                "music part way through.").throw
        };
        ^instrument.asSymbol
    }
}
