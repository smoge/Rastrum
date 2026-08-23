// Note [Scope is a fact about the kind]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A tempo, rehearsal mark or ramp endpoint is score-wide. Engraving
// it over every staff collapses to one row. `\text` keeps its staff;
// system text is not guessed. Nothing is per voice. A direction
// attaches to a bar.


// ScoreDirectionStream: a score's measure directions as flat rows, for reading
// rather than playing.
//
// Companion to `ScoreEventStream`. Directions get their own tree walk
// and row shape. Nothing here plays, schedules or resolves speed. Bar
// starts use `barDuration`, matching the tempo walk.
ScoreDirectionStream {

    // Kinds whose duplicate engraving collapses.
    // Note [Scope is a fact about the kind].
    classvar scoreWideKinds;

    *initClass { scoreWideKinds = [\tempo, \rehearsalMark, \tempoRamp] }

    // A copy, as `ScoreEventStream.requiredKeys` is one.
    //
    // >>> ScoreDirectionStream.scoreWideKinds.includes(\tempo)   -> true
    *scoreWideKinds { ^scoreWideKinds.copy }

    // One row per staff-local direction or score-wide instruction, in order.
    //
    // Rows carry absolute `offset`, bar `measure`, scope, staff
    // indexes and common `Direction` fields.
    //
    // `staffIndexes` names every staff where the direction is written.
    //
    // >>> ScoreDirectionStream.records(
    //     Measure("2/4", "c4 d4").rehearsalMark("A")).size
    // 1
    *records { |element, prepare = true|
        ^this.prWalk(Rastrum.prepared(element, prepare))
    }

    // Rows whose offset falls in `[start, stop)`, matching `ScoreEventStream`.
    //
    // >>> ScoreDirectionStream.between(ScoreDirectionStream.records(
    //     Measure("2/4", "c4 d4").text("solo", "4")), 0, "2").size
    // 1
    *between { |records, start, stop|
        var from = Duration.asDuration(start), to = Duration.asDuration(stop);
        if (to <= from) {
            Error("ScoreDirectionStream.between: window % to % has no length."
                .format(from, to)).throw
        };
        ^records.select { |record|
            (record[\offset] >= from) and: { record[\offset] < to } }
    }

    // Every row of one `Direction` kind.
    //
    // >>> ScoreDirectionStream.ofDirectionKind(ScoreDirectionStream.records(
    //     Measure("2/4", "c4 d4").rehearsalMark("A")), \rehearsalMark).size
    // 1
    *ofDirectionKind { |records, kind|
        var wanted = kind.asSymbol;
        ^records.select { |record| record[\directionKind] == wanted }
    }

    // Every direction that applies to one staff.
    //
    // Score-wide rows apply to every staff.
    //
    // Refuse missing staff indexes when row metadata can check them.
    //
    // >>> ScoreDirectionStream.inStaff(ScoreDirectionStream.records(
    //     MusicScore.staves([(measures: Measure("1/4", "c4").text("pizz."))])),
    //     0).size
    // 1
    *inStaff { |records, staffIndex|
        var rows = records.asArray;
        if (rows.isEmpty) { ^rows };
        this.prCheckStaffIndex(rows.first[\staffCount], staffIndex);
        ^rows.select { |record|
            (record[\scope] == \score)
                or: { record[\staffIndexes].includes(staffIndex) } }
    }

    *prCheckStaffIndex { |count, staffIndex|
        if (staffIndex.isKindOf(Integer).not
            or: { staffIndex < 0 } or: { staffIndex >= count }) {

            Error("ScoreDirectionStream.inStaff: this score has % staff/staves, "
                "so staff % does not exist.".format(
                    count, staffIndex.asCompileString)).throw
        }
    }

    *scoreWide  { |records| ^records.select { |record| record[\scope] == \score } }
    *staffLocal { |records| ^records.select { |record| record[\scope] == \staff } }

    // Walk staff by staff and bar by bar. Callers filter kinds.
    *prWalk { |tree|
        var byKey = Dictionary.new;
        var order = List.new;
        var conflicts = List.new;
        var staves = this.prStavesOf(tree);

        staves.do { |staff, staffIndex|
            // Only a Staff has a staff name.
            var name = if (staff.isKindOf(Staff)) { staff.name } { nil };
            var barStart = Duration(0, 1);

            this.prMeasuresOf(staff).do { |measure, measureIndex|
                var span = if (measure.isKindOf(Measure)) {
                    measure.barDuration
                } {
                    measure.duration * measure.multiplier
                };
                if (measure.isKindOf(Measure)) {
                    measure.directions.do { |direction|
                // Directions are bar-local; rows use absolute offsets.
                        this.prAdd(byKey, order, conflicts,
                            barStart + direction.offset, measureIndex,
                            staffIndex, name, direction)
                    }
                };
                barStart = barStart + span;
            };
        };

        if (conflicts.notEmpty) { this.prRefuseConflict(conflicts.first) };
        // Stamp after the walk; `prAdd` does not need staff count.
        order.do { |row| row[\staffCount] = staves.size };
        ^this.prOrdered(order)
    }

    // Staff-local rows are direct. Score-wide rows collapse by key.
    *prAdd { |byKey, order, conflicts, offset, measureIndex, staffIndex,
        staffName, direction|

        var wide = this.prIsScoreWide(direction);
        var record = this.prRecord(offset, measureIndex, staffIndex, staffName,
            direction, wide);
        var key, seen;

        if (wide.not) { order.add(record); ^this };
        key = this.prKeyOf(offset, direction);
        seen = byKey[key];
        if (seen.isNil) {
            byKey[key] = record;
            order.add(record);
            ^this
        };
        if (this.prSaysTheSame(seen, record).not) {
            conflicts.add([offset, this.prDescribe(seen), this.prDescribe(record)]);
            ^this
        };
        if (seen[\staffIndexes].includes(staffIndex).not) {
            seen[\staffIndexes] = seen[\staffIndexes] ++ [staffIndex]
        };
        ^this
    }

    *prIsScoreWide { |direction| ^scoreWideKinds.includes(direction.kind) }

    *prRecord { |offset, measureIndex, staffIndex, staffName, direction, wide|
        var row = IdentityDictionary.new;
        row[\kind] = \direction;
        row[\directionKind] = direction.kind;
        row[\direction] = direction;
        row[\offset] = offset;
        row[\measure] = measureIndex;
        row[\scope] = if (wide) { \score } { \staff };
        row[\staffIndex] = if (wide) { nil } { staffIndex };
        row[\staff] = if (wide) { nil } { staffName };
        row[\staffIndexes] = [staffIndex];
        row[\text] = direction.text;
        row[\unit] = direction.unit;
        row[\perMinute] = direction.perMinute;
        row[\edge] = direction.edge;
        row[\id] = direction.id;
        ^row
    }

    // What counts as one thing said at one moment.
    *prKeyOf { |offset, direction|
        if (direction.isTempoRamp) {
            ^[offset, direction.kind, direction.edge, direction.id]
        };
        ^[offset, direction.kind]
    }

    // Same means same words and metronome, not same bar-local offset.
    *prSaysTheSame { |a, b|
        ^(a[\text] == b[\text]) and: { a[\unit] == b[\unit] }
            and: { a[\perMinute] == b[\perMinute] }
            and: { a[\edge] == b[\edge] } and: { a[\id] == b[\id] }
    }

    // Ordered by offset, scope, ramp edge, then walk order.
    *prOrdered { |order|
        ^order.asArray.collect { |record, index| [record, index] }
            .sort { |a, b| this.prPrecedes(a, b) }
            .collect { |pair| pair[0] }
    }

    *prPrecedes { |a, b|
        var one = a[0], two = b[0];
        if (one[\offset] != two[\offset]) { ^one[\offset] < two[\offset] };
        if (one[\scope] != two[\scope]) { ^one[\scope] == \score };
        if (this.prRank(one) != this.prRank(two)) {
            ^this.prRank(one) < this.prRank(two)
        };
        ^a[1] <= b[1]
    }

    *prRank { |record|
        if (record[\directionKind] != \tempoRamp) { ^0 };
        if (record[\edge] == \stop) { ^1 };
        ^2
    }

    // The direction as it reads on the page, for conflict messages.
    *prDescribe { |record|
        var parts = List.new;
        parts.add("a " ++ record[\directionKind]);
        record[\edge] !? { |edge| parts.add(edge.asString) };
        record[\text] !? { |text| parts.add("\"" ++ text ++ "\"") };
        record[\unit] !? { |unit|
            parts.add("%/% = %".format(unit.numerator, unit.denominator,
                record[\perMinute]))
        };
        ^parts.join(" ")
    }

    *prStavesOf { |element|
        if (element.isKindOf(MusicScore)) { ^element.children.asArray };
        ^[element]
    }

    *prMeasuresOf { |staff|
        if (staff.isKindOf(Staff)) { ^staff.children.asArray };
        ^[staff]
    }

    // Refused rather than resolved. Text never collapses, so it never
    // reaches here.
    *prRefuseConflict { |entry|
        Error("ScoreDirectionStream: two score-wide directions at %, % and %. "
            "A score-wide moment needs one reading.".format(
                entry[0], entry[1], entry[2])).throw
    }
}
