// PatternWriter: Rastrum events as SuperCollider patterns.
//
// One step past `EventWriter`, and it consumes that class's output rather than
// a score: events in, patterns out. Nothing here reads the tree, and nothing on
// a model class answers to patterns.
//
// A timeline becomes a `Pbind`, because a Pbind is a linear stream of events
// and a timeline is a linear stream of events. Several timelines become a
// `Ppar`, which is what simultaneity is called here. Two voices of a bar both
// begin at zero, and that is a parallel, not a sequence.
//
// A Pbind rather than the shorter `Pseq(events)`, because it is composable in
// the way this ecosystem expects: a caller can lay `\instrument`, `\amp` or
// `\legato` over it with `Pbindf`, or substitute a key, without taking the
// events apart and rebuilding them.
//
// Nothing is played. These are patterns, not players: they stream without a
// Server, and what to do with them belongs to whoever built them.

PatternWriter {
    // The keys carried into the pattern. `\dur` is what schedules. `\rastrum`
    // is the structural payload, passed through untouched. See
    // Note [Structure travels in one payload] in EventWriter.sc.
    classvar <keys;

    *initClass {
        keys = [\type, \midinote, \dur, \rastrum];
    }

    // Returns one Pbind per timeline, in the order the events came in.
    //
    // `extraKeys` is how a layer above says which further SC Event keys its
    // events carry, `\instrument`, `\amp`, `\legato`. Declared rather than
    // discovered, by Note [The all or nothing rule] in PlaybackMap.sc: a key on
    // some events and absent from others ends the stream where it is missing.
    //
    // An argument rather than a mutable classvar, so one caller's
    // interpretation cannot change what every other caller's patterns carry for
    // the session.
    *pbinds { |events, extraKeys|
        var carried = keys ++ (extraKeys ? []);
        ^this.prTimelines(events).collect { |timeline|
            this.prPbindFor(timeline, carried)
        }
    }

    // Returns every timeline at once. Timelines begin together, so they are
    // parallel. A Ppar is how that is said here.
    *pattern { |events, extraKeys| ^Ppar(this.pbinds(events, extraKeys)) }

    // Split where the staff or the timeline changes. `EventWriter` already
    // groups them and keeps each contiguous, so this only has to notice the
    // seam.
    *prTimelines { |events|
        var timelines = List.new;
        var current = nil;
        var key = nil;

        events.do { |event|
            var payload = this.prRastrumOf(event);
            var here = [payload[\staffIndex], payload[\timelineIndex]];
            if (current.isNil or: { here != key }) {
                current = List.new;
                timelines.add(current);
                key = here;
            };
            current.add(event);
        };
        ^timelines.collect { |timeline| timeline.asArray }
    }

    *prRastrumOf { |event|
        var payload = event[\rastrum];
        if (payload.isNil) {
            Error("PatternWriter: event has no \\rastrum payload. Build events with "
                "EventWriter, or provide the namespaced Rastrum metadata yourself.").throw
        };
        [\staffIndex, \timelineIndex].do { |key|
            if (payload[key].isNil) {
                Error("PatternWriter: event \\rastrum payload has no %. Build events "
                    "with EventWriter, or provide complete timeline metadata yourself."
                    .format(key)).throw
            }
        };
        ^payload
    }

    *prPbindFor { |events, carried|
        var pairs = List.new;
        (carried ? keys).do { |key|
            var values = events.collect { |event| this.prValueFor(event, key) };
            case
                { values.every { |value| value.notNil } } {
                    pairs.add(key);
                    pairs.add(Pseq(values, 1));
                }
                // Absent throughout: an interpretation that named no timeline
                // adds no `\instrument`, and a hand-built array may leave an
                // allowed key out. Left out of the Pbind rather than carried,
                // because a nil in a carried key ends the stream at that step.
                { values.every { |value| value.isNil } } { }
                { true } {
                    Error("PatternWriter: % is present on some events of a timeline "
                        "and absent on others. A nil in a Pbind key ends the "
                        "stream there, so this would quietly stop the music part "
                        "way through.".format(key)).throw
                };
        };
        ^Pbind(*pairs.asArray)
    }

    // A rest has no pitch, and nil would end the stream. `Rest` is this
    // language's own way of saying "this step sounds nothing", so it is both
    // non-nil and true, so a reader sees a Rest rather than an invented pitch.
    *prValueFor { |event, key|
        if (key == \midinote and: { event[\midinote].isNil }) { ^Rest() };
        ^event[key]
    }
}
