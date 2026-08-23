// Note [A prose beat is normalized, a written one is kept]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A written mark keeps its beat. This table is interpretation, so it
// stores the quarter-note equivalent the clock needs. `bpm` is
// contextual: without a beat it is quarter BPM; with a beat it is
// normalized from that beat. A metronome mark needs no table. If a
// direction says both prose and `4 = 132`, the written number wins.


// PlaybackTempoMap: a score's tempo directions, in order, with a speed.
//
// Reads where tempo marks are, then decides what prose marks mean.
// Tempo marks belong to score moments, not note events, so they need
// their own list. See Note [Playback interpretation is explicit] in
// Patterns/PlaybackMap.sc. Prose is notation. A number is an
// interpretation. `Direction.tempo("Allegro")` stays prose. Named
// speeds live in this table. Unmapped prose is refused, not guessed.
PlaybackTempoMap {
    // Prose to beats per minute. Not readable directly. See `tempos`.
    var tempos;

    *new { ^super.new.init }

    init {
        tempos = Dictionary.new;
        ^this
    }

    // Answers this, so calls chain. Checks finish before mutation.
    //
    // `beat` names what the count is counted in; the table stores quarter BPM.
    //
    // >>> PlaybackTempoMap.new.tempo("Andante", 72).tempos["Andante"]   -> 72
    // >>> PlaybackTempoMap.new.tempo("Andante", beat: "8", bpm: 144)
    //     .tempos["Andante"]
    // 72.0
    tempo { |text, bpm, beat, unit, perMinute|
        var checkedText = Marking.checkedText(text);
        var count = this.prOneName(bpm, perMinute, "bpm", "perMinute");
        var written = this.prOneName(beat, unit, "beat", "unit");
        var checkedBpm = this.prCheckedBpm(count, text);

        if (written.notNil) {
            checkedBpm = Direction.quarterPerMinuteOf(
                Direction.checkedUnit(written), checkedBpm)
        };
        tempos[checkedText] = checkedBpm;
        ^this
    }

    // A copy. See Note [Maps expose guarded copies] in Patterns/PlaybackMap.sc.
    //
    // >>> PlaybackTempoMap.new.tempos.size   -> 0
    tempos { ^tempos.copy }

    copy {
        var out = PlaybackTempoMap.new;
        tempos.keysValuesDo { |text, bpm| out.tempo(text, bpm) };
        ^out
    }

    // Point tempo directions in order, with this map's speed as `bpm`.
    //
    // Score metronomes win over table entries. `unit` and `perMinute` keep the
    // written mark; `bpm` is quarter BPM.
    //
    // Refuses unmapped prose; silence there would be a hidden performance choice.
    //
    // >>> PlaybackTempoMap.new.records(Measure("1/4", "c4").metronome("4", 120))
    //     .first[\bpm]
    // 120.0
    records { |element, prepare = true|
        var found = PlaybackTempoMap.directionsIn(element, prepare);
        var unmapped = found.select { |record|
            record[\bpm].isNil and: { tempos[record[\text]].isNil }
        };

        if (unmapped.notEmpty) { this.prRefuseUnmapped(unmapped.first) };
        ^found.collect { |record|
            var out = record.copy;
            out[\bpm] = record[\bpm] ?? { tempos[record[\text]] };
            out
        }
    }

    // Tempo changes as SC Events: `(type: \rest, dur:, tempo:, rastrum:)`.
    //
    // Rest events set `~tempo` and sound nothing. `\tempo` is beats
    // per second; score tempi are beats per minute.
    //
    // Two non-change events keep the list schedulable:
    //
    // 1. A *lead-in* when the first change isn't at the start,
    //    carrying `dur` and no `\tempo`. An event stream plays its
    //    first event at once, so without it a tempo written over bar
    //    five would take effect at bar one.
	//
    // 2. A single inert event when the score has no tempo directions.
    //    An empty pattern isn't available to be the no-op:
    //    `ListPattern` refuses a `Pseq([])` where it is built, so the
    //    no-op is one rest of no length.
    //
    // Both omit `\tempo` and `\rastrum`. Ramp steps ride in the same list. The
    // tree is prepared once so point records and ramp endpoints share offsets.
    tempoEvents { |element, prepare = true|
        var tree = Rastrum.prepared(element, prepare);
        ^PlaybackTempoMap.eventsFrom(
            PlaybackTempoMap.prLaneRecords(tree, this.records(tree, false)))
    }

    // Scheduling over records that already carry `bpm`.
    *eventsFrom { |records|
        var out = List.new;

        if (records.isEmpty) { ^[this.prSilence(0)] };
        if (records.first[\offset] > Duration(0, 1)) {
            out.add(this.prSilence(this.prBeats(records.first[\offset])))
        };
        records.do { |record, index|
            var next = records[index + 1];
            var beats = next !? {
                this.prBeats(next[\offset] - record[\offset])
            } ? 0;
            var event = this.prSilence(beats);

            event[\tempo] = record[\bpm] / 60;
            event[\rastrum] = record;
            out.add(event);
        };
        ^out.asArray
    }

    // The music with the score's own numbered tempo marks laid over it.
    //
    // Prose is passed over here; this path only uses numbers written
    // in the score. The caller has already prepared the tree. Point
    // marks and ramp endpoints enter through separate predicates.
    // Both use only numbers the score itself states.
    *withScoreTempo { |pattern, tree|
        var records = this.scoreLaneRecords(tree);

        if (records.isEmpty) { ^pattern };
        ^Ppar([Pseq(this.eventsFrom(records), 1), pattern])
    }

    // The score's numeric tempo lane: point marks plus ramp steps.
    //
    // Table-free so writers can use it without adopting interpretation. Prose
    // resolution stays in `tempoEvents`.
    //
    // >>> PlaybackTempoMap.scoreLaneRecords(
    //     Measure("1/4", "c4").metronome("4", 120)).first[\bpm]
    // 120.0
    *scoreLaneRecords { |tree|
        ^this.prLaneRecords(tree,
            this.prWalk(tree, { |direction| direction.hasMetronome }))
    }

    // The same list as one pattern, to sit beside the music.
    //
    // A `Pseq` of whole Events. A `Pbind` key nil at one step ends
    // the stream. Put it first in the `Ppar`, same-moment tempo
    // changes must reach the clock before the note they govern.
    //
    // Ppar([~map.tempoPattern(~score), Rastrum.pattern(~score)])
    tempoPattern { |element, prepare = true|
        ^Pseq(this.tempoEvents(element, prepare), 1)
    }

    // Note [A ramp is many point changes]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `TempoClock` has point tempi, so ramps become point changes.
    // `prStepBpm` uses average tempo over each step. Written tempo
    // marks win over generated steps at the same offset.

    // Steps per quarter note. Named once so the discretization is one policy.
    *rampStepsPerBeat { ^8 }

    // Matched ramps over a prepared tree, with absolute offsets.
    //
    // `points` decide the speed in force where each ramp begins. The
    // walk carries ramp target speeds forward. A written tempo at the
    // stop overrides the derived arrival. The next ramp then starts
    // from the written tempo.
    *rampsIn { |tree, points|
        var ordered = this.prTempoOrder(points,
            this.prWalk(tree, { |direction| direction.isTempoRamp }));
        var written = points.select { |each| each[\bpm].notNil }
            .collect { |each| each[\offset] };
        var out = List.new;
        var current, open, source;

        ordered.do { |record|
            if (record[\kind] != \tempoRamp) {
                record[\bpm] !? { |bpm| current = bpm };
            } {
                if (record[\edge] == \start) {
                    if (open.notNil) { this.prRefuseOverlap(record) };
                    open = record;
                    source = current;
                } {
                    if (open.isNil) { this.prRefuseUnmatched(record) };
                    if (open[\id] != record[\id]) { this.prRefuseUnmatched(record) };
                    out.add(this.prMatched(open, record, source));
                    // A mark at this offset was read above, so
                    // `current` is already what the lane will play
                    // here.
                    if (written.any { |at| at == record[\offset] }.not) {
                        current = open[\bpm]
                    };
                    open = nil;
                }
            }
        };
        open !? { |dangling| this.prRefuseDangling(dangling) };
        ^out.asArray
    }

    // Point tempo records plus ramp steps: what the lane schedules.
    //
    // Drop derived steps at written tempo marks; notation wins there.
    *prLaneRecords { |tree, points|
        var written = points.collect { |each| each[\offset] };
        var steps = List.new;

        this.rampsIn(tree, points).do { |ramp|
            steps.addAll(this.prStepsOf(ramp).reject { |step|
                written.any { |at| at == step[\offset] } })
        };
        if (steps.isEmpty) { ^points };
        ^(steps.asArray ++ points).sort { |a, b| a[\offset] <= b[\offset] }
    }

    // One ramp as point records, ending with the target at the stop.
    *prStepsOf { |ramp|
        var span = ramp[\stop] - ramp[\start];
        var beats = this.prBeats(span);
        var count = max(1, (beats * this.rampStepsPerBeat).ceil.asInteger);
        var stepSpan = span / count;
        var out = List.new;

        count.do { |i|
            out.add(this.prStepRecord(ramp, ramp[\start] + (stepSpan * i),
                this.prStepBpm(ramp[\sourceBpm], ramp[\targetBpm], beats,
                    beats * i / count, beats * (i + 1) / count)))
        };
        out.add(this.prStepRecord(ramp, ramp[\stop], ramp[\targetBpm]));
        ^out.asArray
    }

    // The constant tempo whose step duration matches the ramp
    // integral. Ramps are linear in written beats.
    //
    // >>> PlaybackTempoMap.prStepBpm(60, 60, 4, 0, 1)   -> 60
    *prStepBpm { |source, target, span, b0, b1|
        var k = (target - source) / span;
        if (k.abs < 1e-12) { ^source };
        ^(b1 - b0) * k / ((source + (k * b1)) / (source + (k * b0))).log
    }

    *prStepRecord { |ramp, offset, bpm|
        ^IdentityDictionary[
            \offset -> offset, \measure -> ramp[\measure], \kind -> \tempoRamp,
            \text -> ramp[\text], \unit -> nil, \perMinute -> nil,
            \bpm -> bpm, \edge -> nil, \id -> ramp[\id], \derived -> true]
    }

    *prMatched { |start, stop, source|
        if (start[\bpm].isNil) { this.prRefuseNoTarget(start) };
        if (source.isNil) { this.prRefuseNoSource(start) };
        if (stop[\offset] <= start[\offset]) { this.prRefuseBackwards(start, stop) };
        ^IdentityDictionary[
            \start -> start[\offset], \stop -> stop[\offset],
            \measure -> start[\measure], \id -> start[\id],
            \text -> start[\text], \sourceBpm -> source,
            \targetBpm -> start[\bpm]]
    }

    // Point marks and ramp endpoints in the order the music meets
    // them. At one offset: the written mark, then a ramp closing,
    // then a ramp opening. A mark written where a ramp begins is the
    // speed that ramp starts from, and a ramp opening where another
    // closes starts from that one's target.
    *prTempoOrder { |points, endpoints|
        ^(points ++ endpoints).sort { |a, b|
            if (a[\offset] == b[\offset]) {
                this.prRank(a) <= this.prRank(b)
            } { a[\offset] < b[\offset] }
        }
    }

    *prRank { |record|
        if (record[\kind] != \tempoRamp) { ^0 };
        if (record[\edge] == \stop) { ^1 };
        ^2
    }

    // A ramp is playable intent. Refuse one that lacks source or target speed.
    *prRefuseNoTarget { |record|
        Error("PlaybackTempoMap: the tempo ramp % at % says how it changes but "
            "not what it changes to. Give the ramp start a target bpm or omit "
            "it from playback.".format(this.prRampName(record), record[\offset])).throw
    }

    *prRefuseNoSource { |record|
        Error("PlaybackTempoMap: the tempo ramp % at % has no speed to start "
            "from. Add a prior metronome mark or map the preceding tempo text."
            .format(this.prRampName(record), record[\offset])).throw
    }

    *prRefuseBackwards { |start, stop|
        Error("PlaybackTempoMap: the tempo ramp % starts at % and stops at %, so "
            "it spans no time.".format(this.prRampName(start), start[\offset], stop[\offset])).throw
    }

    *prRefuseOverlap { |record|
        Error("PlaybackTempoMap: a tempo ramp is still open when % starts at %. "
            "Overlapping tempo ramps are not supported.".format(
                this.prRampName(record), record[\offset])).throw
    }

    *prRefuseUnmatched { |record|
        Error("PlaybackTempoMap: a tempo ramp stop with id % at % closes "
            "nothing.".format(
                record[\id], record[\offset])).throw
    }

    *prRefuseDangling { |record|
        Error("PlaybackTempoMap: the tempo ramp % at % is never closed, so there "
            "is no target moment.".format(
                this.prRampName(record), record[\offset])).throw
    }

    *prRampName { |record|
        ^record[\text] !? { |words| "\"" ++ words ++ "\"" }
            ?? { "with id " ++ record[\id] }
    }

    // A rest that sounds nothing and sets nothing: `dur` and no more.
    *prSilence { |beats| ^(type: \rest, dur: beats) }

    // Whole notes to beats: a quarter is one beat.
    *prBeats { |duration| ^duration.asFloat * 4 }

    // What the score says before prose is resolved. `bpm` is nil for
    // prose. Bar starts use `barDuration`, matching `EventWriter` and
    // pickup bars.
    *directionsIn { |element, prepare = true|
        ^this.prWalk(Rastrum.prepared(element, prepare),
            { |direction| direction.isTempo })
    }

    // The same walk, over tempo-ramp endpoints.
    *rampEndpointsIn { |element, prepare = true|
        ^this.prWalk(Rastrum.prepared(element, prepare),
            { |direction| direction.isTempoRamp })
    }

    // The walk itself, over a prepared and validated tree.
    //
    // The predicate names the direction kind to keep. Filtering
    // happens before conflict checks so `withScoreTempo` can ignore
    // prose it does not interpret.
    *prWalk { |tree, keep|
        var byOffset = Dictionary.new;
        var order = List.new;
        var conflicts = List.new;

        this.prStavesOf(tree).do { |staff|
            var barStart = Duration(0, 1);
            this.prMeasuresOf(staff).do { |measure, measureIndex|
                var span = if (measure.isKindOf(Measure)) {
                    measure.barDuration
                } {
                    measure.duration * measure.multiplier
                };
                if (measure.isKindOf(Measure)) {
                    measure.directions.do { |direction|
                        if (keep.value(direction)) {
                            // Directions are bar-local; the tempo lane is absolute.
                            this.prAdd(byOffset, order, conflicts,
                                barStart + direction.offset, measureIndex,
                                direction)
                        }
                    }
                };
                barStart = barStart + span;
            };
        };

        if (conflicts.notEmpty) { this.prRefuseConflict(conflicts.first) };
        ^order.asArray.sort { |a, b| a[\offset] <= b[\offset] }
    }

    // One record per moment, however many staves say it.
    //
    // A tempo is score-wide: the same mark on several staves is one
    // change. Different marks at one moment conflict. Same means same
    // words and metronome, not same bar-local offset.
    *prAdd { |byOffset, order, conflicts, offset, measureIndex, direction|
        var key = this.prKeyOf(offset, direction);
        var seen = byOffset[key];
        var record = IdentityDictionary[
            \offset -> offset, \measure -> measureIndex,
            \kind -> direction.kind, \text -> direction.text,
            \unit -> direction.unit, \perMinute -> direction.perMinute,
            \bpm -> direction.quarterPerMinute,
            \edge -> direction.edge, \id -> direction.id];

        if (seen.isNil) {
            byOffset[key] = record;
            order.add(record);
            ^this
        };
        if (this.prSaysTheSame(seen, record).not) {
            conflicts.add([offset, this.prDescribe(seen), this.prDescribe(record)])
        };
        ^this
    }

    // What counts as one thing said at one moment.
    //
    // Ramp endpoints include edge and id, so stop/start pairs can
    // share a moment.
    *prKeyOf { |offset, direction|
        if (direction.isTempoRamp) {
            ^[offset, direction.kind, direction.edge, direction.id]
        };
        ^[offset, direction.kind]
    }

    *prSaysTheSame { |a, b|
        ^(a[\text] == b[\text]) and: { a[\unit] == b[\unit] }
            and: { a[\perMinute] == b[\perMinute] }
            and: { a[\edge] == b[\edge] } and: { a[\id] == b[\id] }
    }

    // The mark as it reads on the page, for conflict messages.
    *prDescribe { |record|
        var parts = List.new;
        record[\edge] !? { |edge|
            parts.add("a tempo ramp % (id %)".format(edge, record[\id]))
        };
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

    *prRefuseConflict { |entry|
        Error("PlaybackTempoMap: two tempo directions at %, % and %. One moment "
            "cannot have two tempo readings.".format(
                entry[0], entry[1], entry[2])).throw
    }

    prRefuseUnmapped { |record|
        Error("PlaybackTempoMap: this score is marked \"%\" at % and this map has "
            "no speed for it. Known text tempi: %. Add tempo(\"%\", bpm), or "
            "write a metronome mark.".format(
                record[\text], record[\offset],
                if (tempos.isEmpty) { "none" } {
                    tempos.keys.asArray.sort.collect { |each|
                        "\"" ++ each ++ "\"" }.join(", ")
                },
                record[\text])).throw
    }

    // One fact, one name; refusal names this public call.
    prOneName { |kept, alias, keptName, aliasName|
        if (kept.notNil and: { alias.notNil }) {
            Error("PlaybackTempoMap: use % or %, not both."
                .format(keptName, aliasName)).throw
        };
        ^kept ?? { alias }
    }

    // Beats per minute is positive. Zero is a stopped clock.
    //
    // Fractional values are allowed here; this is interpretation, not notation.
    prCheckedBpm { |bpm, text|
        if (bpm.isNumber.not or: { bpm <= 0 }) {
            Error("PlaybackTempoMap: \"%\" needs a tempo in beats per minute "
                "above zero, got %.".format(text, bpm.asCompileString)).throw
        };
        ^bpm
    }
}
