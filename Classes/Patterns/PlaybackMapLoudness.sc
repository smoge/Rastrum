// Note [Loudness as breakpoints]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Loudness is built as an attack level plus transitions. The scalar
// lowering writes one transition. The envelope lowering writes
// Ref-wrapped `ampLevels` and `ampTimes` at the declared SynthDef
// width. `PlaybackEnvelopeMap` reserves the same keys so two layers
// can't shape one loudness.

+ PlaybackMap {
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
                // Attack over a level.
                //
                // See Note [A sforzando is an accent at a level] in Marking.sc.
                //
                // The record is kept, not only the level, since it is
                // also the step the note settles onto.
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
                        // One dynamic at an attack.
                        // See Note [One loudness slot on a leaf] in Marking.sc.
                        current = record[\marking].value;
                        attackDynamic = record;
                    } {
                        // Inside the event. A hairpin's target
                        // belongs to that ramp, anything else is a
                        // step of its own. Either takes effect after
                        // this event rather than at its attack.
                        if (this.prClosesHairpin(spans, here,
                            record[\offset]).not) {
                                this.prAddStep(steps, event, record)
                        };
                        later.add(record[\marking].value)
                    }
                }
            };

            // `sffz` strikes above ff, then settles onto ff for the
            // rest of that note. A paired dynamic, as in `sfz/p`,
            // gives the settle level and then stays in force like any
            // dynamic.
            //
            // The attack is a zero-length step. It leaves `current`
            // alone unless a dynamic is written beside it.

            accent !? {
                this.prAddStep(steps, event, accent);
                attackDynamic !? { |record|
                    // The level a note settles onto is heard, so it
                    // is checked here. Every other step target is a
                    // level that also becomes some event's own
                    // amplitude and is checked there.
                    if (dynamics[record[\marking].value].isNil) {
                        unknown.add(record[\marking].value)
                    };
                    // The paired dynamic, which is what the attack
                    // settles onto rather than a second mark
                    // contending with it.
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

    // A continuation leaf is not an attack. A sforzando is nothing
    // else. A held note cannot be struck again part way through, so
    // this is refused where a continuation dynamic is honored as a
    // step.
    prRefuseSforzandoInsideEvent { |entry|
        var payload = entry[0];
        var record = entry[1];

        Error(
            "PlaybackMap: % is written % into the tied note at %, so it is not on the "
            "attack. A sforzando is an attack, and a tied note is struck once. Write it on the attack."
            .format(
                Marking.sforzandoSpelling(record[\marking].value),
                record[\offset] - payload[\offset],
                payload[\offset]
            )
        ).throw
    }

    // One step per offset, the latest in time standing. Different
    // offsets become separate transitions for the lowering to accept
    // or refuse.
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
        // A leaf holds at most one dynamic and a timeline one leaf at
        // an offset, so reading the last is defensive rather than a
        // rule.
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
    // at or before it, or the baseline when none is.
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

    // Note [A hairpin is a ramp, not an attack]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Hairpins and continuation dynamics can change one tied event,
    // so they can't be only `\amp`. The scalar lowering carries one
    // transition on `\amp`, `\ampEnd`, `\ampRampStart` and
    // `\ampRampDur`. More transitions need the breakpoint lowering
    // below. Hairpins must close on a dynamic or playback would
    // invent a destination.

    // One event's loudness as an attack level and timed transitions.
    // `\name` belongs only to refusals. Durations stay exact here
    // because these values are compared against each other. A ramp
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
        // A step is a transition of no length: hold, then jump where
        // it was written.
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

    // Default scalar lowering.
    //
    // See Note [A hairpin is a ramp, not an attack].
    //
    // Four scalar keys and one transition. Beats rather than exact
    // Durations, as `\dur` is: these schedule against it and a ramp
    // is interpretation rather than written time.
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
            event[\ampRampStart] = EventWriter.prBeats(change[\at]);
            event[\ampRampDur] = EventWriter.prBeats(change[\dur]);
        };
    }

    // The opt-in lowering: many transitions as one `Env` shape.
    // `\amp` stays the attack level.
    prWriteAmpEnvelope { |event, payload, shape|
        var levels = [shape[\attack]];
        var times = [];
        var at = Duration(0);
        var level = shape[\attack];

        shape[\changes].do { |change|
            // Hold what is in force until the transition begins.
            if (change[\at] > at) {
                times = times.add(EventWriter.prBeats(change[\at] - at));
                levels = levels.add(level);
            };
            times = times.add(EventWriter.prBeats(change[\dur]));
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

    // Straight in time between the two ends. A curve would be a policy and
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

    prRefuseOverlappingHairpins { |at|
        Error(
            "PlaybackMap: a hairpin begins at % while another is still open. "
            "Overlapping hairpins are not supported."
            .format(at)
        ).throw
    }

    prRefuseUnpairedHairpin { |at, edge|
        Error(
            "PlaybackMap: a hairpin % at % has no matching end."
            .format(edge, at)
        ).throw
    }

    prRefuseEmptyHairpin { |at|
        Error("PlaybackMap: hairpin at % spans no time.".format(at)).throw
    }

    prRefuseHairpinWithoutTarget { |start, stop, direction|
        Error(
            "PlaybackMap: the % from % to % ends on no dynamic, so it has no target "
            "amplitude. Mark the closing leaf."
            .format(direction, start, stop)
        ).throw
    }

    prRefuseDynamicInsideHairpin { |at, name, start, stop|
        Error(
            "PlaybackMap: % at % is inside the hairpin from % to %, so the ramp and "
            "dynamic both claim that note. Write it at an endpoint."
            .format(name, at, start, stop)
        ).throw
    }

    prRefuseHairpinDirection { |start, stop, direction, from, to|
        Error(
            "PlaybackMap: the % from % to % runs % to %, the wrong way for the written "
            "hairpin."
            .format(direction, start, stop, from, to)
        ).throw
    }

    // Scalar ramp keys describe one transition. The envelope lowering carries
    // more, so name it in the refusal.
    prRefuseSecondTransition { |payload, change|
        Error(
            "PlaybackMap: % is written % into the tied note at %, which already changes "
            "once. One held note can carry one internal dynamic step. Call "
            "useLoudnessEnvelopes for more."
            .format(
                change[\name] ?? { "a hairpin" },
                change[\at],
                payload[\offset]
            )
        ).throw
    }

    // A wider shape can't be sent without truncating a played level.
    prRefuseSegments { |payload, wanted|
        Error(
            "PlaybackMap: the note at % changes loudness over % envelope segments and "
            "this map was asked for %. Call useLoudnessEnvelopes(%), and declare "
            "ampLevels with % channels."
            .format(
                payload[\offset],
                wanted,
                loudnessSegments,
                wanted,
                wanted + 1
            )
        ).throw
    }

    prCheckedSegments { |segments|
        if (segments.isKindOf(Integer).not or: { segments < 1 }) {
            Error(
                "PlaybackMap: useLoudnessEnvelopes needs a segment count of one or more, not %."
                .format(segments.asCompileString)
            ).throw
        };
        ^segments
    }

    // Only reachable from a table built by `dynamic` alone:
    // `useDynamics` installs every name in `Marking.dynamics` and
    // `Marking.dynamic` admits no other.
    prRefuseUnknownDynamic { |name|
        Error(
            "PlaybackMap: this score is marked % and the dynamics table has no "
            "amplitude for it. Known dynamics: %. Call useDynamics, or dynamic(%, amp)."
            .format(
                name,
                dynamics.keys.asArray.collect { |each| each.asString }
                .sort.join(", "),
                name.asCompileString
            )
        ).throw
    }
}
