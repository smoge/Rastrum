// PatternWriter: Rastrum events as SuperCollider patterns.
//
// Events in, patterns out. Each timeline becomes one `Pbind`.
// Simultaneous timelines are joined by `Ppar`. Nothing here reads the
// score or plays sound.

PatternWriter {
    // Keys carried into the pattern. `\dur` schedules; `\rastrum` is
    // payload.
    classvar <keys;

    *initClass {
        keys = [\type, \midinote, \dur, \rastrum];
    }

    // Answers one Pbind per timeline, in the order the events came in.
    //
    // `extraKeys` names further SC Event keys such as `\instrument`, `\amp` and
    // `\legato`.
    //
    // An argument, not mutable class state.
    //
    // >>> PatternWriter.pbinds(EventWriter.events(Measure("2/4", "c4 d4"))).size
    // 1
    *pbinds { |events, extraKeys|
        var carried = keys ++ (extraKeys ? []);
        ^this.prTimelines(events).collect { |timeline|
            this.prPbindFor(timeline, carried)
        }
    }

    // Answers every timeline at once. Timelines begin together.
    //
    // >>> PatternWriter.pattern(EventWriter.events(Measure("2/4", "c4 d4"))).class
    // Ppar
    *pattern { |events, extraKeys| ^Ppar(this.pbinds(events, extraKeys)) }

    // Split where staff or timeline changes.
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
            Error("PatternWriter: event has no \\rastrum payload. Use "
                "EventWriter or provide complete Rastrum metadata.").throw
        };
        [\staffIndex, \timelineIndex].do { |key|
            if (payload[key].isNil) {
                Error("PatternWriter: event \\rastrum payload has no %. Use "
                    "EventWriter or provide complete timeline metadata."
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
                // Absent throughout: leave the key out of the Pbind.
                { values.every { |value| value.isNil } } { }
                { true } {
                    Error("PatternWriter: % is present on some events of a timeline "
                        "and absent on others. A carried Pbind key must be all "
                        "present or all absent.".format(key)).throw
                };
        };
        ^Pbind(*pairs.asArray)
    }

    // A rest has no pitch, and nil would end the stream. Use SC's `Rest`.
    *prValueFor { |event, key|
        if (key == \midinote and: { event[\midinote].isNil }) { ^Rest() };
        ^event[key]
    }
}
