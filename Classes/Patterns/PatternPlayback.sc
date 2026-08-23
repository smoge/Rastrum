// PatternPlayback: convenience over patterns
//
// Playback side-effect boundary. `playable` answers the pattern to
// play; `play` starts it.
//
// Server boot and SynthDef installation belong to the SC session.
PatternPlayback {

    // Overlay only the explicit overrides. nil for both answers
    // `pattern`.
    //
    // `Pbindf` overrides. There is no instrument or amp default here;
    // SC's default event already has them. An amp override flattens
    // every loudness key.
    //
    // >>> { |p| PatternPlayback.playable(p, nil, nil) === p }
    //     .value(Pbind(\dur, 1))   -> true
    // >>> PatternPlayback.playable(Pbind(\dur, 1), \sine, 0.2).class  -> Pbindf
    *playable { |pattern, instrument, amp|
        var pairs = List.new;
        instrument !? { pairs.add(\instrument); pairs.add(instrument) };
        amp !? {
            pairs.addAll([\amp, amp, \ampEnd, amp,
                \ampRampStart, 0.0, \ampRampDur, 0.0,
                // Preserve event width when flattening a loudness envelope.
                \ampLevels, Pfunc { |event|
                    Ref(Array.fill(this.prWidth(event, \ampLevels, 2), amp)) },
                \ampTimes, Pfunc { |event|
                    Ref(Array.fill(this.prWidth(event, \ampTimes, 1), 0.0)) }])
        };
        if (pairs.isEmpty) { ^pattern };
        ^Pbindf(pattern, *pairs.asArray)
    }

    // Width already on the event, or a flat default.
    *prWidth { |event, key, otherwise|
        var found = event !? { event[key] };
        if (found.isKindOf(Ref).not) { ^otherwise };
        ^found.value.size
    }

    // Starts the pattern and answers the player.
    *play { |pattern, instrument, amp, clock|
        if (Server.default.serverRunning.not) {
            "PatternPlayback: no server is running, so nothing will be heard. "
            "Boot one with s.boot and play again.".warn
        };
        ^this.playable(pattern, instrument, amp).play(clock ?? { TempoClock.default })
    }
}
