// Rastrum
//
// The front door. Everything real lives in the model and writer classes, so
// most of what follows is one line over one of them. Only `render` needs an
// external binary. Not the place to start reading. See Note [Reading order] in
// Duration.sc.
Rastrum {
    classvar <lilypondPath;
    classvar <>outputDirectory;
    classvar <>lilypondVersion = "2.25.35";

    // Whether `preview` does anything. The example runner sets it false so a
    // smoke run engraves nothing.
    classvar <>previews = true;

    *initClass {
        StartUp.add {
            outputDirectory = Platform.userAppSupportDir +/+ "rastrum-output";
            lilypondPath = lilypondPath ?? { this.findLilypond };
        }
    }

    *version { ^"0.1.0" }

    // Set by hand when discovery missed, or found the wrong binary. Checked
    // here rather than at the next render, where a typo surfaces as a shell
    // failure inside `runLilypond` naming nothing that leads back here. nil
    // means no LilyPond, which is what discovery answers when there is none.
    *lilypondPath_ { |path|
        if (path.notNil and: { File.exists(path).not }) {
            Error("Rastrum: % is not a file. Give the LilyPond binary itself - "
                "`which lilypond` prints its path - or nil to clear it."
                .format(path.asCompileString)).throw
        };
        lilypondPath = path;
        ^this
    }

    // A *login* shell, or Homebrew's PATH is missing on Apple Silicon and
    // LilyPond fails deep inside Guile rather than at the call site. The two
    // branches are different vocabularies, not opinions: `where` is a zsh
    // builtin on Linux, so `unixCmdGetStdOut` would not find it at all.
    *findLilypond {
        var cmd = if (this.isWindows) {
            "where lilypond"
        } {
            "$SHELL -lc 'command -v lilypond'"
        };
        ^this.prFirstNonEmptyLine(cmd.unixCmdGetStdOut)
    }

    // Splitting on `Char.nl` alone leaves the `\r` of a Windows CRLF on the
    // path, to fail much later with no mention of discovery in it. Its own
    // method so that shape is testable off Windows.
    *prFirstNonEmptyLine { |text|
        ^text.split(Char.nl)
            .collect { |line| line.stripWhiteSpace }
            .detect { |line| line.notEmpty }
    }

    *isWindows { ^thisProcess.platform.name == \windows }

    *prepareOutputDirectory {
        if (File.exists(outputDirectory).not) { File.mkdir(outputDirectory) };
        ^outputDirectory
    }

    // Any writer, any extension, over a prepared tree, so a structural mistake
    // fails here rather than inside whichever writer trips over it, or in
    // LilyPond's case fails to. Nothing is written on failure.
    //
    // Which means rendering before opening the file, not inside it. `File.use`
    // truncates on open, so a writer that refuses partway (a divisions count
    // past the ceiling, a tie with nothing to land on, a grace leaf carrying
    // what the format cannot say) would leave an empty file wearing a real
    // extension, which reads as a written score rather than as a failure.


    // Note [A derived beam is not a score fact]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `AutoBeam` writes the endpoints a person would, and the model has no mark
    // saying which of the two put them there. Nothing downstream can tell a
    // derived group from an authored one, so where the pass may run is a
    // decision rather than a convenience.
    //
    // A rendering is where engraving policy belongs, so `render`, `preview` and
    // `writeMusicXML` beam by default. A ScoreJSON document is not a rendering:
    // it is what the author wrote, handed to another program that would take a
    // derived group as authored. So `writeJSON` does not beam, and a caller who
    // wants it on the wire runs `AutoBeam.run` first, which makes the mutation
    // theirs. The two products differ by derived beams, and an authored beam
    // still reaches both.
    //
    // Only on the prepared tree, which this method built. `AutoBeam` works in
    // place, so beaming under `prepare: false` would mutate the caller's score,
    // and that flag already means "write what I gave you".
    *writeFile { |element, writer, name, extension, prepare = true, beam = false|
        var dir = this.prepareOutputDirectory;
        var base = name ? ("rastrum-" ++ Date.localtime.stamp);
        var path = dir +/+ (base ++ "." ++ extension);
        var tree = if (prepare) { ScorePrepare.run(element) } { element };
        var text;
        if (beam and: { prepare }) { AutoBeam.run(tree) };
        Validator.validate(tree, prepare);
        text = writer.write(tree);
        File.use(path, "w", { |f| f.write(text) });
        ^path
    }

    // Only a score is a MusicXML document. See Note [Only a score is a
    // document] in MusicXMLWriter.sc: below a MusicScore the writer emits
    // measures with no `<score-partwise>` or `<part-list>` around them, which
    // is a useful fragment and not a file any reader opens. A `.musicxml`
    // extension promises otherwise, so this refuses rather than writing one.
    // `ScoreJSONWriter` refuses the same thing in the writer itself, and
    // LilyPond needs no such rule, a fragment there being ordinary LilyPond.
    *writeMusicXML { |element, name, prepare = true, beam = true|
        if (element.isKindOf(MusicScore).not) {
            Error("Rastrum: a % is not a MusicXML document, only a fragment of "
                "one. Wrap it in a MusicScore before writing a file."
                .format(element.class)).throw
        };
        ^this.writeFile(element, MusicXMLWriter.new, name, "musicxml", prepare, beam)
    }

    // No `beam`, by Note [A derived beam is not a score fact]. Run
    // `AutoBeam.run` yourself if the wire should carry them.
    *writeJSON { |element, name, prepare = true|
        ^this.writeFile(element, ScoreJSONWriter.new, name, "json", prepare)
    }

    // Validated on the way in, as `writeFile` validates on the way out. The
    // reader is a decoder: it holds a document to the schema, and the schema
    // cannot say whether a 4/4 bar is full or whether a tie has anywhere to
    // land. Those are the same refusals a writer depends on, so the facade
    // makes them here rather than handing back a tree that fails later.
    //
    // `ScoreJSONReader.read` is the raw route for a caller who wants the tree a
    // document describes rather than one this quark will write.
    *readJSON { |path|
        var tree = ScoreJSONReader.read(File.readAllString(path));
        Validator.validate(tree);
        ^tree
    }

    // See `EventWriter`.
    *events { |element, prepare = true|
        ^EventWriter.events(this.prepared(element, prepare))
    }

    // The tree everything here works from: prepared if asked, validated either
    // way. The flag reaches `Validator` too, where some checks only hold of a
    // prepared tree.
    //
    // Public because more than one caller needs *the same* tree, not an
    // equivalent one. `pattern` reads events and tempo marks from one score,
    // and preparing twice would leave two trees agreeing by luck.
    *prepared { |element, prepare = true|
        var tree = if (prepare) { ScorePrepare.run(element) } { element };
        Validator.validate(tree, prepare);
        ^tree
    }

    // See `PatternWriter`.
    *pbinds { |element, prepare = true|
        ^PatternWriter.pbinds(this.events(element, prepare))
    }

    // A Ppar over the timelines, carrying the score's own metronome marks
    // unless `tempo: false`, which is what to pass when composing a
    // `PlaybackTempoMap`'s `tempoPattern` beside this, so the two do not both
    // set the clock. `pbinds` never carries tempo: a tempo is not a timeline.
    *pattern { |element, prepare = true, tempo = true|
        var tree = this.prepared(element, prepare);
        var music = PatternWriter.pattern(EventWriter.events(tree));
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    // See `PatternPlayback.playable`, including why neither has a default.
    *playable { |element, instrument, amp, prepare = true, tempo = true|
        ^PatternPlayback.playable(
            this.pattern(element, prepare, tempo), instrument, amp)
    }

    // The only method here that starts something running. Everything it
    // decides is in `playable`.
    //
    // `clock` and the score's own marks are not rivals. The clock is where it
    // starts and a mark is a change written into the music, so a marked score
    // overrides it from the first mark onward. Pass `tempo: false`, or an
    // unmarked score, and the clock is the whole of it.
    *play { |element, instrument, amp, prepare = true, clock, tempo = true|
        ^PatternPlayback.play(
            this.pattern(element, prepare, tempo), instrument, amp, clock)
    }

    // `midi` adds the `\midi { }` block, so LilyPond writes a .midi beside the
    // .pdf, on by default, and a metronome mark reaches it at the speed it
    // prints.
    //
    // `layout` names a `\layout` block, and is passed through rather than kept
    // anywhere. See Note [A layout is the writer's, not the model's] in
    // LilyWriter.sc: spacing is a LilyPond concept, so the score never learns
    // the word and no other backend has to answer for it.
    // `beam` derives beam groups from the meter before writing, since this
    // writer switches LilyPond's own inference off and an unbeamed page is not
    // what anyone means by default. See
    // Note [A derived beam is not a score fact] above, and
    // Note [What a first policy admits] in AutoBeam.sc for what it declines.
    *render { |element, name, compile = true, open = true, prepare = true,
              midi = true, layout = \default, beam = true|
        var base = name ? ("rastrum-" ++ Date.localtime.stamp);
        var lyPath = this.writeFile(
            element, LilyWriter.new(midi, layout), base, "ly", prepare, beam);

        if (compile.not) { ^lyPath };
        if (lilypondPath.isNil) {
            Error("Rastrum: LilyPond was not found on PATH at startup, so there "
                "is nothing to engrave with. Install it, or say where it is:\n"
                "    Rastrum.lilypondPath = \"/path/to/lilypond\";\n"
                "The .ly is written either way, at %.".format(lyPath)).throw
        };
        this.checkLilypondSpeaksTheFile(element);

        this.runLilypond(lyPath, outputDirectory +/+ base);
        if (open) { this.openPDF(outputDirectory +/+ (base ++ ".pdf")) };
        ^lyPath
    }

    // `render` with the arguments a person looking at a score always wants,
    // behind the `previews` switch above. Answers the `.ly` path, or nil when
    // previews are off.
    *preview { |element, name, prepare = true, layout = \default, beam = true|
        if (previews.not) { ^nil };
        ^this.render(element, name, true, true, prepare, true, layout, beam)
    }

    // Public so a rendered file can be opened again without re-running
    // LilyPond.
    *openPDF { |path|
        if (path.isNil or: { File.exists(path).not }) {
            Error("Rastrum: PDF not found at %. Render first, or pass a PDF path "
                "that exists.".format(path.asCompileString)).throw
        };
        path.openOS;
        ^path
    }

    // Note [The writer declares a version, render meets a binary]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `LilyWriter` spells a grouped meter from `Rastrum.lilypondVersion`, and
    // deliberately never asks what is installed: the same score and the same
    // declared version must give the same `.ly` on every machine. Asking the
    // toolchain is this class's job, because this is the layer that runs it.
    //
    // Which spelling is which is Note [Two spellings for one grouped meter] in
    // LilyWriter.sc. What matters here is what handing the wrong one to a
    // binary looks like: `unknown command: \compoundMeter` from inside
    // LilyPond, which is true and useless about which of the two versions to
    // change.
    //
    // Only when there is a grouped meter to spell, and only when compiling. A
    // score without one writes `\time 5/8` and every version reads that.
    // `installed` is an argument so the check can be asked about a version
    // without a LilyPond to ask, which is what makes it testable anywhere. Left
    // out, it discovers the binary that render is about to invoke.
    *checkLilypondSpeaksTheFile { |element, installed|
        var declaredNew, installedNew;
        if (this.prHasGroupedMeter(element).not) { ^this };
        installed = installed ?? { this.installedLilypondVersion };
        if (installed.isNil) { ^this };
        declaredNew = LilyWriter.prVersionAtLeast(lilypondVersion, [2, 25, 34]);
        installedNew = LilyWriter.prVersionAtLeast(installed, [2, 25, 34]);
        if (declaredNew == installedNew) { ^this };
        Error("Rastrum: this score has a grouped meter, and the two LilyPonds "
            "disagree about how to spell one. Written for the declared version "
            "%, which uses %; the installed LilyPond is %, which uses %. Either "
            "set Rastrum.lilypondVersion to \"%\", or render with a LilyPond on "
            "the same side of 2.25.34 as the version declared.".format(
                lilypondVersion,
                this.prGroupedMeterSpelling(declaredNew),
                installed,
                this.prGroupedMeterSpelling(installedNew),
                installed)).throw
    }

    *prGroupedMeterSpelling { |usesComplexTime|
        ^if (usesComplexTime) {
            "\\time #'((2 3) . 8)"
        } {
            "\\compoundMeter #'((2 3 8))"
        }
    }

    // The version of the binary that would run, or nil if it will not say. nil
    // is not an error, since guessing would refuse a render that might work.
    *installedLilypondVersion {
        var reported;
        if (lilypondPath.isNil) { ^nil };
        reported = ("% --version".format(this.prQuotedPath(lilypondPath)))
            .unixCmdGetStdOut;
        ^reported.findRegexp("[0-9]+\\.[0-9]+\\.[0-9]+").collect { |match|
            match[1] }.first
    }

    *prHasGroupedMeter { |element|
        var found = false;
        if (element.respondsTo(\traverse).not) { ^false };
        element.traverse { |node|
            if (node.isKindOf(Measure) and: { node.meter.notNil }
                and: { node.meter.isGrouped }) { found = true }
        };
        ^found
    }

    *runLilypond { |lyPath, outBase|
        var cmd = "% -dno-point-and-click -o % %".format(
            this.prQuotedPath(lilypondPath),
            this.prQuotedPath(outBase),
            this.prQuotedPath(lyPath)
        );
        // Homebrew builds need a login shell so that `gs` is on PATH.
        if (this.isWindows.not and: { lilypondPath.contains("homebrew") }) {
            cmd = "$SHELL -lc %".format(cmd.quote)
        };
        ^this.prCheckedRun(cmd.systemCmd, lyPath)
    }

    // A path, quoted for the shell that will read it. Both `systemCmd` and
    // `unixCmdGetStdOut` reach one.
    //
    // `shellQuote` is POSIX-only by design and cmd does not read `'` as a quote
    // at all, so a POSIX-quoted `C:\Program Files\...` arrives split on its
    // spaces. Double quotes are cmd's, and need no escaping for a path, since a
    // `\"` cannot appear in a Windows filename.
    //
    // For a path only. cmd expands `%VAR%` inside double quotes, and a value
    // ending in a backslash meets the C runtime's argument parser.
    //
    // Tested as far as Linux allows: both spellings, with the POSIX one
    // asserted to equal `shellQuote`. Cmd actually accepting the result wants a
    // Windows machine and is still owed.
    *prQuotedPath { |path| ^this.prQuotedFor(path, this.isWindows) }

    // The platform is an argument rather than a lookup, so both spellings can
    // be tested on a machine that is only one of them.
    *prQuotedFor { |path, windows|
        ^if (windows) { path.quote } { path.shellQuote }
    }

    // The status is the only thing that says whether it worked: LilyPond writes
    // *everything* to stderr, success line included, so an IDE that labels
    // stderr has already labeled the success an error.
    //
    // Unchecked, a failed engrave answered a `.ly` path as though it had
    // worked and `render` opened the stale PDF from the last run that did,
    // silently showing yesterday's score.
    //
    // `systemCmd` answers a wait status, not an exit code, so exiting 1 arrives
    // as 256. Reported raw: 32512 is more searchable than the 127 it decodes
    // to.
    *prCheckedRun { |status, lyPath|
        if (status != 0) {
            Error("Rastrum: LilyPond did not finish (status %) on %. Its own "
                "diagnosis is in the post window - LilyPond writes everything to "
                "stderr, so an IDE may have labeled it ERROR whether it "
                "succeeded or failed. No PDF was opened.".format(
                    status, lyPath)).throw
        };
        ^status
    }
}
