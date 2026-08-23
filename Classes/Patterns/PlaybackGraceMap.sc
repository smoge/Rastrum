// Note [A grace length is chosen, not read]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A grace leaf's written duration is display value only. This layer chooses the
// playback length.

// Note [Two styles, two lenders]
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
//
// A plain grace sounds *before* the beat and borrows from the
// previous event in the same timeline. An acciaccatura sounds *on*
// the beat and borrows from its host.
//
// Written offsets do not move. Only top-level `dur` changes.
//
// An acciaccatura needs no lender, so it is the one shape that plays
// at the start of a timeline.
//
// Refuse no previous event, not enough duration, or a graced continuation.


// PlaybackGraceMap: grace notes as events, spliced in before their host.
//
// Separate from `PlaybackMap` because it inserts events and shortens
// one. Composed by `PlaybackProfile`. `spliced` is the hook it
// reaches for.
//
// `EventWriter` stays structural. This reads grace groups from the
// prepared tree and matches them to events by `[staffIndex,
// timelineIndex, offset]`.
PlaybackGraceMap {
    var length;

    // A sixteenth. Short enough to read as an ornament, long enough to hear.
    classvar <defaultLength;

    *initClass { defaultLength = Duration(1, 16) }

    *new { ^super.new.init }

    init { length = PlaybackGraceMap.defaultLength; ^this }

    // Answers this, so calls chain.
    //
    // >>> PlaybackGraceMap.new.length                  -> Duration(1/16)
    // >>> PlaybackGraceMap.new.length_("32").length    -> Duration(1/32)
    length_ { |value|
        var checked = Duration.asDuration(value);
        if (checked <= Duration(0, 1)) {
            Error("PlaybackGraceMap: grace length must be positive, got %."
                .format(checked)).throw
        };
        length = checked;
        ^this
    }

    length { ^length }

    // The events of `element` with every grace group spliced in.
    //
    // `map` is kept only to refuse the old composition shape clearly.
    //
    // >>> PlaybackGraceMap.new.events(
    //     Measure("2/4", [MN("c4").acciaccatura(MN("b8")), MN("d4")])).size
    // 3
    events { |element, map, prepare = true|
        var tree;
        map !? { this.prRefusePeer(map) };
        tree = Rastrum.prepared(element, prepare);
        ^this.spliced(Rastrum.events(tree, false), tree)
    }

    // One Ppar over the spliced timelines. This layer adds no keys of its own.
    pattern { |element, map, prepare = true, tempo = true|
        var tree, music;
        map !? { this.prRefusePeer(map) };
        tree = Rastrum.prepared(element, prepare);
        music = PatternWriter.pattern(this.events(tree, nil, false), []);
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    // One Pbind per timeline, for a caller who wants them apart.
    pbinds { |element, map, prepare = true|
        map !? { this.prRefusePeer(map) };
        ^PatternWriter.pbinds(this.events(element, nil, prepare), [])
    }

    // Compose playback layers with `PlaybackProfile`.
    prRefusePeer { |map|
        Error("PlaybackGraceMap: compose playback layers with PlaybackProfile, "
            "not this method. Got %.".format(
                map.class.name)).throw
    }

    // The core, over events and the tree they came from.
    //
    // Events are copied first. `remaining` tracks what each lender still has.
    //
    // >>> { |bar| PlaybackGraceMap.new.spliced(Rastrum.events(bar), bar).size }
    //     .value(Measure("2/4", [MN("c4").acciaccatura(MN("b8")), MN("d4")]))
    // 3
    spliced { |events, tree|
        var hosts = ScoreSelection(tree).withGraces;
        var out = events.collect { |event| event.copy };
        var remaining = IdentityDictionary.new;
        if (hosts.isEmpty) { ^out };
        out.do { |event| remaining[event] = event[\rastrum][\duration] };
        hosts.records.do { |record|
            out = this.prSpliceOne(out, record, remaining) };
        ^out
    }

    // One group, by Note [Two styles, two lenders].
    prSpliceOne { |events, record, remaining|
        var leaf = record[\leaf];
        var borrowed = length * leaf.graces.size;
        var at = this.prHostIndex(events, record);

        case
        { leaf.graceStyle == \grace } {
            ^this.prSpliceBefore(events, record, remaining, at, borrowed)
        }
        { leaf.graceStyle == \acciaccatura } {
            ^this.prSpliceOnBeat(events, record, remaining, at, borrowed)
        }
        { true } { this.prRefuseStyle(leaf.graceStyle) };
    }

    // The previous event lends; the host stays where written.
    prSpliceBefore { |events, record, remaining, at, borrowed|
        var from = this.prLenderIndex(events, at, record);
        var lender, left;

        if (from.isNil) { this.prRefuseFirst(record) };
        lender = events[from];
        left = remaining[lender];
        if (left <= borrowed) { this.prRefuseBorrow(record, borrowed, left) };
        remaining[lender] = left - borrowed;
        lender[\dur] = this.prBeats(left - borrowed);
        ^this.prWith(events, at, record[\leaf], borrowed)
    }

    // The host lends; the group starts where the host was written.
    prSpliceOnBeat { |events, record, remaining, at, borrowed|
        var host = events[at];
        var left = remaining[host];

        if (left <= borrowed) { this.prRefuseBorrow(record, borrowed, left) };
        remaining[host] = left - borrowed;
        host[\dur] = this.prBeats(left - borrowed);
        ^this.prWith(events, at, record[\leaf], Duration(0, 1))
    }

    // Insert the group before the host, shifted earlier by `shift`.
    prWith { |events, at, leaf, shift|
        ^events.copyRange(0, at - 1)
            ++ this.prGraceEvents(leaf, events[at], shift)
            ++ events.copyRange(at, events.size - 1)
    }

    // Where the host sounds. Use the first non-grace event at that place.
    prHostIndex { |events, record|
        var found = events.detectIndex { |event|
            var payload = event[\rastrum];
            (payload[\grace] != true)
                and: { payload[\staffIndex] == record[\staffIndex] }
                and: { payload[\timelineIndex] == record[\voiceIndex] }
                and: { payload[\offset] == record[\offset] }
        };
        if (found.isNil) { this.prRefuseUnmatched(record) };
        ^found
    }

    // The event before the host in its own timeline.
    //
    // Skip inserted graces; ornaments lend nothing.
    prLenderIndex { |events, at, record|
        var index = at - 1;
        while { index >= 0 } {
            var payload = events[index][\rastrum];
            if (payload[\grace] != true) {
                if ((payload[\staffIndex] == record[\staffIndex])
                    and: { payload[\timelineIndex] == record[\voiceIndex] }) {
                        ^index
                };
                ^nil
            };
            index = index - 1;
        };
        ^nil
    }

    // One event per grace leaf, each a copy of the host.
    prGraceEvents { |leaf, host, shift|
        var start = host[\rastrum][\offset] - shift;
        ^leaf.graces.collect { |grace, index|
            var event = host.copy;
            var at = start + (length * index);
            event[\type] = \note;
            event[\midinote] = this.prMidinoteOf(grace);
            event[\dur] = this.prBeats(length);
            event[\rastrum] = this.prPayloadFor(host[\rastrum], at);
            // An ornament isn't part of a ramp.
            event[\ampEnd] !? {
                event[\ampEnd] = event[\amp];
                event[\ampRampStart] = 0.0;
                event[\ampRampDur] = 0.0;
            };
            event
        }
    }

    prMidinoteOf { |grace|
        if (grace.isKindOf(Chord)) {
            ^grace.pitches.collect { |pitch| pitch.midinote }
        };
        ^grace.pitch.midinote
    }

    // Host payload, with this grace's own time and no attachments.
    prPayloadFor { |host, at|
        var payload = IdentityDictionary.new;
        host.keysValuesDo { |key, value| payload[key] = value };
        payload[\offset] = at;
        payload[\duration] = length;
        payload[\rest] = false;
        payload[\markings] = [];
        payload[\spanners] = [];
        payload[\grace] = true;
        ^payload
    }

    prBeats { |duration| ^duration.asFloat * 4 }

    prRefuseStyle { |style|
        Error("PlaybackGraceMap: unsupported grace style %."
            .format(style.asCompileString)).throw
    }

    prRefuseFirst { |record|
        Error("PlaybackGraceMap: the grace at % begins staff % timeline %, so "
            "there is no previous event to borrow from. Use acciaccatura for "
            "on-beat borrowing.".format(record[\offset],
                record[\staffIndex], record[\voiceIndex])).throw
    }

    prRefuseBorrow { |record, borrowed, remaining|
        Error("PlaybackGraceMap: the grace group at % wants % and the note it "
            "borrows from has % left. Shorten the group or grace length."
            .format(record[\offset], borrowed, remaining)).throw
    }

    prRefuseUnmatched { |record|
        Error("PlaybackGraceMap: the grace at % is not on an attack. It is on a "
            "tied continuation.".format(record[\offset])).throw
    }
}
