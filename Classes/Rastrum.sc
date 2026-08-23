// Rastrum
//
// The facade. Most methods are one line over the model, writers or playback
// layers. Only `render` runs an external binary.
Rastrum {
    classvar <lilypondPath;
    classvar <>outputDirectory;
    classvar <>lilypondVersion = "2.25.35";

    // Whether `preview` does anything.
    classvar <>previews = true;

    *initClass {
        StartUp.add {
            outputDirectory = Platform.userAppSupportDir +/+ "rastrum-output";
            lilypondPath = lilypondPath ?? { this.findLilypond };
        }
    }

    // >>> Rastrum.version   -> 0.1.0
    *version { ^"0.1.0" }

    // Set by hand when discovery missed or found the wrong binary. nil means
    // no LilyPond.
    *lilypondPath_ { |path|
        if (path.notNil and: { File.exists(path).not }) {
            Error("Rastrum: lilypondPath must be a file path or nil, got %."
                .format(path.asCompileString)).throw
        };
        lilypondPath = path;
        ^this
    }

    // Use a login shell on Unix so Homebrew paths are visible. Windows uses
    // `where`.
    *findLilypond {
        var cmd = if (this.isWindows) {
            "where lilypond"
        } {
            "$SHELL -lc 'command -v lilypond'"
        };
        ^this.prFirstNonEmptyLine(cmd.unixCmdGetStdOut)
    }

    // Trim CRLF and blank lines from discovery output.
    //
    // >>> Rastrum.prFirstNonEmptyLine("\n  lilypond\n")   -> lilypond
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


    // Note [One public path, one stated order]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `events`, `pbinds`, `pattern`, `playable` and `play` all accept
    // the same optional `profile`. The facade delegates layer order
    // to `PlaybackProfile`. `playable` and `play` only wrap the
    // pattern it answers.

    // Note [A derived beam is not a score fact]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Auto beams are engraving policy. Rendered outputs may derive
    // them. ScoreJSON keeps what the author wrote. Beaming runs only
    // on the prepared copy, because `AutoBeam` edits in place.
	//

    // Prepare and validate before opening the file. `File.use`
    // truncates on open, so writer refusals must happen first.
    *writeFile { |element, writer, name, extension, prepare = true, beam = false|
        var dir = this.prepareOutputDirectory;
        var base = name ? ("rastrum-" ++ Date.localtime.stamp);
        var path = dir +/+ (base ++ "." ++ extension);
        var text = this.textFor(element, writer, prepare, beam);
        File.use(path, "w", { |f| f.write(text) });
        ^path
    }

    // The document text, through the same path `writeFile` uses before disk.
    *textFor { |element, writer, prepare = true, beam = false|
        var tree = if (prepare) { ScorePrepare.run(element) } { element };
        if (beam and: { prepare }) { AutoBeam.run(tree) };
        Validator.validate(tree, prepare);
        ^writer.write(tree)
    }

    // Note [The document, read rather than written]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // Text helpers keep the facade path: prepare, optionally beam,
    // validate, then spell. `midi` defaults off because these methods
    // answer text.

    // The LilyPond text of `element`.
    //
    // >>> Rastrum.lily(Measure("2/4", "c4 d4")).contains("\\time 2/4")   -> true
    *lily { |element, prepare = true, beam = true, layout = \default, midi = false|
        ^this.textFor(element, LilyWriter.new(midi, layout), prepare, beam)
    }

    // GUIDO Music Notation. Beams are derived by default, as for LilyPond and
    // MusicXML. `GuidoWriter` refuses lossy facts by name.
    *guido { |element, prepare = true, beam = true|
        ^this.textFor(element, GuidoWriter.new, prepare, beam)
    }

    *writeGuido { |element, name, prepare = true, beam = true|
        ^this.writeFile(element, GuidoWriter.new, name, "gmn", prepare, beam)
    }

    // Only a score is a MusicXML file. Below `MusicScore`, the writer
    // emits a useful fragment, a `.musicxml` extension would promise
    // a document.
    *writeMusicXML { |element, name, prepare = true, beam = true|
        if (element.isKindOf(MusicScore).not) {
            Error("Rastrum: writeMusicXML needs a MusicScore document, got %."
                .format(element.class)).throw
        };
        ^this.writeFile(element, MusicXMLWriter.new, name, "musicxml", prepare, beam)
    }

    // No `beam`, by Note [A derived beam is not a score fact]. An
    // explicit `AutoBeam.run` pass puts derived beams on the wire.
    *writeJSON { |element, name, prepare = true|
        ^this.writeFile(element, ScoreJSONWriter.new, name, "json", prepare)
    }

    // Validate on the way in, as `writeFile` validates on the way out. The
    // schema decodes shape; the validator checks musical placement.
    //
    // `ScoreJSONReader.read` is the raw decoder.
    *readJSON { |path|
        var tree = ScoreJSONReader.read(File.readAllString(path));
        Validator.validate(tree);
        ^tree
    }

    // See Patterns/EventWriter.sc.
    //
    // `profile` is optional and is where interpretation enters, by
    // Note [One public path, one stated order].
    *events { |element, prepare = true, profile|
        var found = this.prCheckedProfile(profile);
        found !? { ^found.events(element, prepare) };
        ^EventWriter.events(this.prepared(element, prepare))
    }

    // The shared tree for facade methods: prepared if asked, validated either
    // way. `pattern` reads events and tempo marks from this same tree.
    *prepared { |element, prepare = true|
        var tree = if (prepare) { ScorePrepare.run(element) } { element };
        Validator.validate(tree, prepare);
        ^tree
    }

    // See Patterns/PatternWriter.sc.
    *pbinds { |element, prepare = true, profile|
        var found = this.prCheckedProfile(profile);
        found !? { ^found.pbinds(element, prepare) };
        ^PatternWriter.pbinds(this.events(element, prepare))
    }

    // A Ppar over the timelines. `tempo: false` leaves clock tempo to
    // an external `PlaybackTempoMap`. `pbinds` never carries tempo.
    *pattern { |element, prepare = true, tempo = true, profile|
        var found = this.prCheckedProfile(profile);
        var tree, music;
        found !? { ^found.pattern(element, prepare, tempo) };
        tree = this.prepared(element, prepare);
        music = PatternWriter.pattern(EventWriter.events(tree));
        if (tempo.not) { ^music };
        ^PlaybackTempoMap.withScoreTempo(music, tree)
    }

    // See Patterns/PatternPlayback.sc, including why neither has a default.
    // Profiles are applied by `pattern`; `playable` only wraps its result.
    *playable { |element, instrument, amp, prepare = true, tempo = true, profile|
        ^PatternPlayback.playable(
            this.pattern(element, prepare, tempo, profile), instrument, amp)
    }

    // The only method here that starts playback. `clock` sets the starting
    // tempo; written tempo marks then take over unless `tempo: false`.
    *play { |element, instrument, amp, prepare = true, clock, tempo = true,
        profile|

        ^PatternPlayback.play(
            this.pattern(element, prepare, tempo, profile), instrument, amp,
            clock)
    }

    // nil or a `PlaybackProfile`, checked here so the refusal names
    // the public argument.
    *prCheckedProfile { |profile|
        if (profile.isNil) { ^nil };
        if (profile.isKindOf(PlaybackProfile).not) {
            Error("Rastrum: profile takes a PlaybackProfile or nil, got %. "
                "Build one with PlaybackProfile.new and its map slots."
                .format(profile.class.name)).throw
        };
        ^profile
    }

    // `midi` adds the `\midi { }` block. `layout` is a profile name
    // or a `LilyProfile`; the writer owns that formatting policy.
    // `beam` derives meter-based beams on the prepared copy.
    *render { |element, name, compile = true, open = true, prepare = true,
              midi = true, layout = \default, beam = true|
        var base = name ? ("rastrum-" ++ Date.localtime.stamp);
        var lyPath = this.writeFile(
            element, LilyWriter.new(midi, layout), base, "ly", prepare, beam);

        if (compile.not) { ^lyPath };
        if (lilypondPath.isNil) {
            Error("Rastrum: LilyPond was not found on PATH. Install it or set:\n"
                "    Rastrum.lilypondPath = \"/path/to/lilypond\"\n"
                "The .ly file was still written at %.".format(lyPath)).throw
        };
        this.checkLilypondSpeaksTheFile(element);

        this.runLilypond(lyPath, outputDirectory +/+ base);
        if (open) { this.openPDF(outputDirectory +/+ (base ++ ".pdf")) };
        ^lyPath
    }

    // `render` with preview defaults. Answers the `.ly` path, or nil
    // when previews are off.
    *preview { |element, name, prepare = true, layout = \default, beam = true|
        if (previews.not) { ^nil };
        ^this.render(element, name, true, true, prepare, true, layout, beam)
    }

    // Public facade over the quasiquoter. Nothing installs it
    // automatically: the interpreter has one preprocessor slot,
    // shared by all quarks.

    // Refuses an occupied preprocessor slot unless `force`, which
    // then hands that preprocessor to `stopQuasiquoter` to put back.
    *startQuasiquoter { |force = false| ^RastrumQuasiquoter.start(force) }

    // Put back what `startQuasiquoter` found, if it is still safe to do so.
    *stopQuasiquoter { ^RastrumQuasiquoter.stop }

    *quasiquoterActive { ^RastrumQuasiquoter.active }

    // Public so a rendered file can be opened again without re-running LilyPond.
    *openPDF { |path|
        if (path.isNil or: { File.exists(path).not }) {
            Error("Rastrum: PDF not found at %. Render first or pass an existing "
                "PDF path.".format(path.asCompileString)).throw
        };
        path.openOS;
        ^path
    }

    // Note [The writer declares a version, render meets a binary]
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //
    // `LilyWriter` follows `Rastrum.lilypondVersion`. It does not
    // inspect the installed binary. `render` is the layer that can
    // compare them. The check only matters for grouped meters, and
    // only when compiling. `installed` keeps it testable without
    // discovering a binary.


    *checkLilypondSpeaksTheFile { |element, installed|
        var declaredNew, installedNew;
        if (this.prHasGroupedMeter(element).not) { ^this };
        installed = installed ?? { this.installedLilypondVersion };
        if (installed.isNil) { ^this };
        declaredNew = LilyWriter.prVersionAtLeast(lilypondVersion, [2, 25, 34]);
        installedNew = LilyWriter.prVersionAtLeast(installed, [2, 25, 34]);
        if (declaredNew == installedNew) { ^this };
        Error("Rastrum: grouped-meter spelling mismatch. Declared LilyPond % "
            "uses %, installed % uses %. Set Rastrum.lilypondVersion to \"%\" "
            "or use a matching LilyPond binary.".format(
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

    // The version of the binary that would run, or nil if it will not say.
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
        var cmd = this.prLilypondCommand(lyPath, outBase);
        // Homebrew builds need a login shell so that `gs` is on PATH.
        if (this.isWindows.not and: { lilypondPath.contains("homebrew") }) {
            cmd = "$SHELL -lc %".format(cmd.quote)
        };
        ^this.prCheckedRun(cmd.systemCmd, lyPath)
    }

    // Split out so tests can read the command without running LilyPond.
    // `--loglevel=WARN` keeps progress chatter off stderr but preserves warnings.
    *prLilypondCommand { |lyPath, outBase|
        ^"% --loglevel=WARN -dno-point-and-click -o % %".format(
            this.prQuotedPath(lilypondPath),
            this.prQuotedPath(outBase),
            this.prQuotedPath(lyPath)
        )
    }

    // Quote a path for the shell that will read it. POSIX uses `shellQuote`;
    // Windows cmd uses double quotes. This is for paths only.
    *prQuotedPath { |path| ^this.prQuotedFor(path, this.isWindows) }

    // Platform is an argument so both spellings can be tested anywhere.
    //
    // >>> Rastrum.prQuotedFor("a b", false) == "a b".shellQuote   -> true
    *prQuotedFor { |path, windows|
        ^if (windows) { path.quote } { path.shellQuote }
    }

    // The status is the only thing that says whether it worked:
    // LilyPond writes to stderr either way, so the text can't be read
    // as a verdict. Unchecked, a failed engrave answered a `.ly` path
    // as though it had worked and `render` opened the stale PDF from
    // the last run that did, silently showing yesterday's score.
    // `systemCmd` answers a wait status, not an exit code, so exiting
    // 1 arrives as 256. Reported raw: 32512 is more searchable than
    // the 127 it decodes to.
    *prCheckedRun { |status, lyPath|
        if (status != 0) {
            Error("Rastrum: LilyPond failed with status % on %. See the post "
                "window. No PDF was opened.".format(status, lyPath)).throw
        };
        ^status
    }
}
