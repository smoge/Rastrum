// ScoreLocator: given a moment, what the score says there.
//
// The inward companion to `ScoreEventStream` and
// `ScoreDirectionStream`: one moment or window asked of the same
// rows.
//
// Read-only. No patching, transport state, scheduling, writer output,
// playback output or wire key.
//
// Takes the same element shapes as `ScoreSelection`.


// Note [A moment is a point, a note is a span]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Two windowing rules:
//
//     a marking, a spanner endpoint, a direction   own offset in [start, stop)
//     an event                                     overlaps [start, stop)
//
// A held note can appear in adjacent windows. `row[\offset] >= start`
// separates an attack from a note already sounding.
//
// `at` and `between` answer stream rows. `selectionAt` is the written
// view: a tie is one event but several leaves.


// Note [A prepared address is not an authored one]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// Every path this class answers addresses `source`. By default that
// is a prepared copy, and preparation may change leaf paths. A
// prepared path can name another leaf in the score handed in, or none
// at all.
//
// A caller who will EDIT reads with `prepare: false`. `ScoreEdit`'s
// run methods can refuse a prepared selection because a selection
// carries source. Path methods cannot, because a path does not.
ScoreLocator {
    // The tree this locator reads, prepared unless the caller said otherwise.
    // Note [A prepared address is not an authored one].
    var <source;

    // `ScoreEventStream`'s rows, `ScoreDirectionStream`'s rows, and a
    // `ScoreSelection` over the same tree.
    var <records, <directionRecords, <selection;

    // Prepare once for all three readers. `prepare: false` keeps edit paths
    // authored.
    //
    // >>> ScoreLocator(Measure("2/4", "c4 d4")).eventsAt("1/4").size   -> 1
    *new { |element, prepare = true|
        var tree = Rastrum.prepared(element, prepare);
        ^super.newCopyArgs(tree,
            ScoreEventStream.records(tree, false),
            ScoreDirectionStream.records(tree, false),
            ScoreSelection(tree))
    }

    // Everything the score says at one instant: every event sounding there,
    // and every point written exactly there.
    // Note [A moment is a point, a note is a span].
    //
    // >>> ScoreLocator(Measure("2/4", "c4 d4").rehearsalMark("A")).at(0).size
    // 2
    at { |offset|
        var here = Duration.asDuration(offset);
        ^this.eventsAt(here) ++ this.attachmentsAt(here)
            ++ this.directionsAt(here)
    }

    // The same over a half-open window.
    between { |start, stop|
        this.prCheckWindow(start, stop);
        ^this.eventsBetween(start, stop) ++ this.attachmentsBetween(start, stop)
            ++ this.directionsBetween(start, stop)
    }

    // Every event whose span contains this moment. Rests count too.
    //
    // >>> ScoreLocator(Measure("1/2", "c2")).eventsAt("1/4").size   -> 1
    eventsAt { |offset|
        var here = Duration.asDuration(offset);
        ^this.prEventRows.select { |row|
            (row[\offset] <= here)
                and: { here < (row[\offset] + row[\duration]) } }
    }

    // Every event overlapping `[start, stop)`.
    eventsBetween { |start, stop|
        var from = Duration.asDuration(start), to = Duration.asDuration(stop);
        this.prCheckWindow(from, to);
        ^this.prEventRows.select { |row|
            (row[\offset] < to)
                and: { (row[\offset] + row[\duration]) > from } }
    }

    // Markings and spanner endpoints written exactly here.
    attachmentsAt { |offset|
        var here = Duration.asDuration(offset);
        ^this.prAttachmentRows.select { |row| row[\offset] == here }
    }

    // The stream's own half-open window.
    attachmentsBetween { |start, stop|
        this.prCheckWindow(start, stop);
        ^ScoreEventStream.between(this.prAttachmentRows, start, stop)
    }

    // Measure directions standing exactly here.
    directionsAt { |offset|
        var here = Duration.asDuration(offset);
        ^directionRecords.select { |row| row[\offset] == here }
    }

    directionsBetween { |start, stop|
        this.prCheckWindow(start, stop);
        ^ScoreDirectionStream.between(directionRecords, start, stop)
    }

    // The written view: leaves sounding now, as a `ScoreSelection`.
    //
    // Not the same answer as `eventsAt`: a tie is one event and
    // several leaves.
    //
    // `ScoreSelection.startingAt` asks about attacks. This asks what
    // sounds.
    //
    // Note [A prepared address is not an authored one] on editing these paths.
    //
    // >>> ScoreLocator(Measure("2/4", "c4 d4")).selectionAt("1/4").size   -> 1
    selectionAt { |offset|
        var here = Duration.asDuration(offset);
        ^selection.where { |record|
            (record[\offset] <= here)
                and: { here < (record[\offset] + record[\prolated]) } }
    }

    // Every leaf overlapping the window, using `ScoreSelection`'s rule.
    selectionBetween { |start, stop|
        this.prCheckWindow(start, stop);
        ^selection.overlapping(start, stop)
    }

    prEventRows { ^ScoreEventStream.ofKind(records, \event) }

    prAttachmentRows { ^records.select { |row| row[\kind] != \event } }

    // Check here so the message names this call.
    prCheckWindow { |start, stop|
        var from = Duration.asDuration(start), to = Duration.asDuration(stop);
        if (to <= from) {
            Error("ScoreLocator: window % to % has no length. A window is half "
                "open, so it needs a stop after its start."
                .format(from, to)).throw
        };
        ^[from, to]
    }
}
