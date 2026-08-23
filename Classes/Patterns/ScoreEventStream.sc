// Note [A row is a moment, an event is a span]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// An event has length. A marking or spanner endpoint is a point. An
// attachment row carries both its own `offset` and the event's
// `eventOffset`. Rows keep `EventWriter` order: staff, timeline,
// event, then attachments. One writing is one row. Attachments are
// identified by offset and object.

// ScoreEventStream: structural events as flat rows, for reading rather than
// playing.
//
// One row per thing that happens, with exact time. Nothing here
// plays, schedules, or changes lower layers. It reads `EventWriter`
// events and their `\rastrum` payload. Events with no payload are
// refused.
ScoreEventStream {

    // Every payload key a row needs. Names are optional and may be nil.
    classvar requiredKeys;

    *initClass {
        requiredKeys = [\offset, \duration, \staffIndex, \timelineIndex,
            \measure, \rest, \markings, \spanners];
    }

    // A copy, as `PlaybackMap`'s tables are.
    //
    // >>> ScoreEventStream.requiredKeys.includes(\offset)   -> true
    *requiredKeys { ^requiredKeys.copy }

    // >>> ScoreEventStream.records(Measure("2/4", "c4 d4")).size   -> 2
    *records { |element, prepare = true|
        ^this.recordsFromEvents(Rastrum.events(element, prepare))
    }

    // The same over events somebody already has.
    *recordsFromEvents { |events|
        var out = List.new;
        var seen = Dictionary.new;
        events.do { |event, index|
            var payload = this.prPayloadOf(event, index);
            var key = [payload[\staffIndex], payload[\timelineIndex]];
            // In two steps: `seen[key] = list` answers the Dictionary.
            var here = seen[key];
            if (here.isNil) { here = List.new; seen[key] = here };
            out.add(this.prEventRow(event, payload));
            this.prAttachmentRows(payload, here).do { |row| out.add(row) };
        };
        ^out.asArray
    }

    // Rows whose own offset falls in `[start, stop)`.
    //
    // >>> ScoreEventStream.between(
    //     ScoreEventStream.records(Measure("2/4", "c4 d4")), 0, "4").size
    // 1
    *between { |records, start, stop|
        var from = Duration.asDuration(start), to = Duration.asDuration(stop);
        if (to <= from) {
            Error("ScoreEventStream.between: window % to % has no length."
                .format(from, to)).throw
        };
        ^records.select { |record|
            (record[\offset] >= from) and: { record[\offset] < to } }
    }

    // Every row of one timeline.
    //
    // >>> ScoreEventStream.inTimeline(
    //     ScoreEventStream.records(Measure("2/4", "c4 d4")), 0, 0).size
    // 2
    *inTimeline { |records, staffIndex, timelineIndex|
        ^records.select { |record|
            (record[\staffIndex] == staffIndex)
                and: { record[\timelineIndex] == timelineIndex } }
    }

    // >>> ScoreEventStream.ofKind(
    //     ScoreEventStream.records(Measure("2/4", "c4 d4")), \event).size
    // 2
    *ofKind { |records, kind| ^records.select { |record| record[\kind] == kind } }

    // `midinote` and `dur` come from the Event. Exact values stay in payload.
    *prEventRow { |event, payload|
        var row = this.prWhere(payload);
        row[\kind] = \event;
        row[\offset] = payload[\offset];
        row[\duration] = payload[\duration];
        row[\rest] = payload[\rest];
        row[\midinote] = event[\midinote];
        row[\dur] = event[\dur];
        ^row
    }

    // One row per attachment, at its own offset.
    *prAttachmentRows { |payload, seen|
        var rows = List.new;
        payload[\markings].do { |record|
            if (this.prFirstSeen(seen, record[\offset], record[\marking])) {
                rows.add(this.prAttachmentRow(payload, \marking, \marking, record))
            }
        };
        payload[\spanners].do { |record|
            if (this.prFirstSeen(seen, record[\offset], record[\spanner])) {
                rows.add(this.prAttachmentRow(payload, \spanner, \spanner, record))
            }
        };
        ^rows.asArray.sort { |a, b| a[\offset] <= b[\offset] }
    }

    // Whether this attachment is new at this offset, recording it if so.
    *prFirstSeen { |seen, at, object|
        if (seen.any { |each| each[0] == at and: { each[1] === object } }) {
            ^false
        };
        seen.add([at, object]);
        ^true
    }

    *prAttachmentRow { |payload, kind, key, record|
        var row = this.prWhere(payload);
        row[\kind] = kind;
        row[\offset] = record[\offset];
        row[\eventOffset] = payload[\offset];
        row[key] = record[key];
        ^row
    }

    // Where a row is, alike for every kind.
    *prWhere { |payload|
        var row = IdentityDictionary.new;
        row[\staffIndex] = payload[\staffIndex];
        row[\timelineIndex] = payload[\timelineIndex];
        row[\staff] = payload[\staff];
        row[\voice] = payload[\voice];
        row[\measure] = payload[\measure];
        ^row
    }

    // Refuse events outside `EventWriter`'s payload contract.
    *prPayloadOf { |event, index|
        var payload = event !? { event[\rastrum] };
        if (payload.isNil) {
            Error("ScoreEventStream: event % has no \\rastrum payload. Use "
                "EventWriter.".format(index)).throw
        };
        requiredKeys.do { |key|
            if (payload[key].isNil) {
                Error("ScoreEventStream: event % payload has no %. Use "
                    "EventWriter.".format(index, key)).throw
            }
        };
        ^payload
    }
}
