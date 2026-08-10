// PatternPlayback: convenience over patterns
//
// Everything else in this project answers a question: a tree, a string, a file
// path, an event, a pattern. This is where that stops. `play` starts something
// running and returns the player, and that is a side effect however small the
// method is.
//
// So it is split in two. `playable` builds the pattern and answers it, which is
// the whole of the decision making and is testable without a server or a sound.
// `play` is one line over it, with nothing in it to get wrong.
//
// It assumes a booted Server and a SynthDef by the name given. Neither is
// checked beyond a warning, because neither is this project's to arrange:
// choosing an instrument is composition, and booting a server is the user's
// session.
//
// This is convenience over SuperCollider patterns, not notation. Nothing here
// knows what a note head is, and no model class answers to it.
PatternPlayback {

    // The pattern with whichever of `\instrument` and `\amp` were asked for
    // laid over it, by `Pbindf`, which overrides the keys it is given and
    // leaves the rest, so nil for both answers the pattern unchanged.
    //
    // Neither has a default, because overriding is what `Pbindf` does: `0.1`
    // would silently flatten every `\amp` a dynamics interpretation had set,
    // and `\default` every `\instrument` a staff mapping had. Nothing is lost
    // by dropping them, SC's own default event already says both, `\default`
    // and `~db.dbamp` at `db: -20.0`, which is 0.1.
    // >>> { |p| PatternPlayback.playable(p, nil, nil) === p }
    //     .value(Pbind(\dur, 1))   -> true
    // >>> PatternPlayback.playable(Pbind(\dur, 1), \sine, 0.2).class  -> Pbindf
    *playable { |pattern, instrument, amp|
        var pairs = List.new;
        instrument !? { pairs.add(\instrument); pairs.add(instrument) };
        amp !? { pairs.add(\amp); pairs.add(amp) };
        if (pairs.isEmpty) { ^pattern };
        ^Pbindf(pattern, *pairs.asArray)
    }

    // Returns the player, so the caller can stop it. This is the side effect.
    //
    // The warning is the one courtesy worth paying for: without a server the
    // pattern runs and nothing is heard, which looks like a bug in the music
    // rather than a session that was not started.
    *play { |pattern, instrument, amp, clock|
        if (Server.default.serverRunning.not) {
            "PatternPlayback: no server is running, so nothing will be heard. "
            "Boot one with s.boot and play again.".warn
        };
        ^this.playable(pattern, instrument, amp).play(clock ?? { TempoClock.default })
    }
}
