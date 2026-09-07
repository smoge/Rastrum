+ PlaybackMap {
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

    // A continuation-leaf articulation is about release, not attack. This layer
    // doesn't model release shaping yet.
    prRefuseArticulationInsideEvent { |entry|
        var payload = entry[0];
        var record = entry[1];

        Error(
            "PlaybackMap: % is written % into the tied note at %, so it is not on the "
            "attack. Write attack articulations on the attack."
            .format(
                record[\marking].value,
                record[\offset] - payload[\offset],
                payload[\offset]
            )
        ).throw
    }

    prRefuseUnknownArticulationLoudness { |name|
        Error(
            "PlaybackMap: this score is marked % and the loudness table has no "
            "multiplier for it. Known articulations: %. Call useArticulationLoudness, "
            "or articulationLoudness(%, factor)."
            .format(
                name,
                articulationLoudnesses.keys.asArray
                .collect { |each| each.asString }.sort.join(", "),
                name.asCompileString
            )
        ).throw
    }

    // Only reachable from a table built by `articulation` alone.
    prRefuseUnknownArticulation { |name|
        Error(
            "PlaybackMap: this score is marked % and the articulation table has no "
            "legato for it. Known articulations: %. Call useArticulations, or "
            "articulation(%, legato)."
            .format(
                name,
                articulations.keys.asArray
                .collect { |each| each.asString }.sort.join(", "),
                name.asCompileString
            )
        ).throw
    }
}
