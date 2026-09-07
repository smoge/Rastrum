+ PlaybackMap {
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
            Error(
                "PlaybackMap: no timeline in this score is staff % voice %. Target by "
                "index with instrumentAt."
                .format(staffName, voiceName)
            ).throw
        };
        Error(
            "PlaybackMap: staff % voice % is % timelines, so it does not say which to "
            "map. Target by index with instrumentAt."
            .format(staffName, voiceName, entry[2])
        ).throw
    }

    // sclang does not sort Array pairs directly.
    prRefuseIndex { |key, present|
        Error(
            "PlaybackMap: this score has no staff % timeline %. It has %."
            .format(
                key[0],
                key[1],
                present.asArray.collect { |each| each.asString }
                .sort.join(", ")
            )
        ).throw
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
                Error(
                    "PlaybackMap: % index must be a non-negative Integer, got %."
                    .format(
                        ["staff", "timeline"][position],
                        value.asCompileString
                    )
                ).throw
            }
        };
        ^[staffIndex, timelineIndex]
    }
}
