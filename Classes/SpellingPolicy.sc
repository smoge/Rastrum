// SpellingPolicy: named, reusable spelling choices.
//
// `MusicPitch` builds and checks candidates. A policy only chooses.
// It carries configuration, not call state.
//
// `fallback:` handles misses. `base:` is for pass-like rules that
// rewrite a complete context-free spelling. Composed policies inherit
// their slots' passage needs.

SpellingPolicy {
    // name: Symbol, chooser: Function, requiresWholePassage: Boolean
    // builtInSpelling: Symbol, the Symbol this policy is equal to, or nil
    var <name, chooser, <requiresWholePassage, <builtInSpelling, resolver;

    // Built-in spellings as policies for nested slots.
    //
    // >>> MusicPitch.fromNumber(61, SpellingPolicy.minimal).spelling   -> c#[4]
    // >>> MusicPitch.fromNumber(61, 1%/2, SpellingPolicy.minimal).spelling   -> d-[4]
    //
    // ^ Self
    *minimal { ^this.prBuiltIn(\minimal) }

    // >>> MusicPitch.fromNumber(61, SpellingPolicy.sharps).spelling   -> c#[4]
    //
    // ^ Self
    *sharps { ^this.prBuiltIn(\sharps) }

    // >>> MusicPitch.fromNumber(61, SpellingPolicy.flats).spelling   -> db[4]
    //
    // ^ Self
    *flats { ^this.prBuiltIn(\flats) }

    // Ordered `[noteName, accidental]` rows. First hit wins; misses
    // fall back.
    //
    // >>> MusicPitch.fromNumber(70, SpellingPolicy.minimal).spelling   -> a#[4]
    // >>> MusicPitch.fromNumber(70, SpellingPolicy.prefer([[\b, \flat]])).spelling   -> bb[4]
    // >>> MusicPitch.fromNumber(60, SpellingPolicy.prefer([[\b, \flat]])).spelling    -> c[4]
    //
    // ^ Self
    *prefer { |rows, fallback = \minimal|
        var wanted = this.prCheckedRows(rows);
        var behind = this.prCheckedSpelling(fallback, "prefer's fallback");

        ^this.prNew(\prefer, { |ctx|
            this.prFirstNamed(wanted, ctx) ?? { this.prSpell(behind, ctx) }
        }, this.prNeedsWholePassage([behind]))
    }

    // Keep one note name when a candidate exists; otherwise fall back.
    //
    // >>> MusicPitch.fromNumber(61, SpellingPolicy.keepNoteName(\d)).spelling   -> db[4]
    // >>> MusicPitch.fromNumber(65, SpellingPolicy.keepNoteName(\b)).spelling   -> f[4]
    //
    // ^ Self
    *keepNoteName { |noteName, fallback = \minimal|
        var wanted = this.prCheckedNoteName(noteName, "keepNoteName");
        var behind = this.prCheckedSpelling(fallback, "keepNoteName's fallback");

        ^this.prNew(\keepNoteName, { |ctx|
            ctx[\candidates].detect { |each| each[\noteName] == wanted }
                ?? { this.prSpell(behind, ctx) }
        }, this.prNeedsWholePassage([behind]))
    }

    // One spelling per motion direction. Scalar calls use `same`.
    //
    // >>> PitchMaterial.leafRows([[60], [61], [62]], SpellingPolicy.byMotion).collect { |each| each.pitch.spelling }
    // [ c[4], c#[4], d[4] ]
    // >>> PitchMaterial.leafRows([[62], [61], [60]], SpellingPolicy.byMotion).collect { |each| each.pitch.spelling }
    // [ d[4], db[4], c[4] ]
    //
    // ^ Self
    *byMotion { |up = \sharps, down = \flats, same = \minimal|
        var rising = this.prCheckedSpelling(up, "byMotion's up");
        var falling = this.prCheckedSpelling(down, "byMotion's down");
        var level = this.prCheckedSpelling(same, "byMotion's same");

        ^this.prNew(\byMotion, { |ctx|
            var direction = ctx[\direction];
            var chosen = case
                { direction == \up } { rising }
                { direction == \down } { falling }
                { level };

            this.prSpell(chosen, ctx)
        }, this.prNeedsWholePassage([rising, falling, level]))
    }

    // Local microtonal sandwich rule. If the base spelling shares a
    // written position, note name and octave, with both neighbors, move
    // the microtone to the adjacent line.
    //
    // Neighbors are respelled from height, so row order cannot change it.
    //
    // >>> PitchMaterial.leafRows([[62], [123%/2], [62]], \minimal).collect { |each| each.pitch.spelling }
    // [ d[4], d-[4], d[4] ]
    // >>> PitchMaterial.leafRows([[62], [123%/2], [62]], SpellingPolicy.distinctLines).collect { |each| each.pitch.spelling }
    // [ d[4], c#+[4], d[4] ]
    //
    // ^ Self
    *distinctLines { |base = \minimal|
        var spelling = this.prCheckedBase(base);

        ^this.prNew(\distinctLines, { |ctx| this.prDistinctLine(spelling, ctx) })
    }

    // Private passage policy: one positional answer per context.
    // Positional answers avoid keying chord members by context fields.
    //
    // ^ Self
    *prPassagePolicy { |name, resolver|
        ^this.prNew(name, { |ctx| ctx[\minimal] }, true).prSetResolver(resolver)
    }

    // ^ Self
    prSetResolver { |function| resolver = function; ^this }

    // >>> SpellingPolicy.minimal.prAnswersPassage   -> false
    //
    // ^ Boolean
    prAnswersPassage { ^resolver.notNil }

    // The caller checks the length and every answer.
    //
    // ^ [Event | MusicPitch]
    prValueAll { |contexts|
        if (resolver.isNil) { ^contexts.collect { |ctx| this.value(ctx) } };
        ^resolver.value(contexts)
    }

    // Private test policy for doors that cannot call whole passages.
    //
    // >>> SpellingPolicy.prWholePassage.requiresWholePassage   -> true
    // >>> SpellingPolicy.minimal.requiresWholePassage          -> false
    //
    // ^ Self
    *prWholePassage { |name = \wholePassage|
        ^this.prNew(name, { |ctx| ctx[\minimal] }, true)
    }

    // Guard for passage-capable doors. A whole-passage policy must
    // have a resolver.
    //
    // >>> try { SpellingPolicy.prCheckedPassageCapable(SpellingPolicy.prWholePassage, "x") } { |e| e.what.contains("nothing to call") }
    // true
    // >>> SpellingPolicy.prCheckedPassageCapable(\flats, "x")   -> flats
    //
    // ^ Symbol | Function | SpellingPolicy
    *prCheckedPassageCapable { |policy, label|
        if (policy.isKindOf(SpellingPolicy)
            and: { policy.requiresWholePassage }
            and: { policy.prAnswersPassage.not }) {
            Error(
                "%: % needs the whole passage and answers no passage resolver, "
                "so there is nothing to call.".format(label, policy)
            ).throw
        };
        ^policy
    }

    // Guard for doors that call one pitch at a time. `doing` names the
    // door.
    //
    // >>> try { SpellingPolicy.prCheckedIncremental(SpellingPolicy.prWholePassage, "x", "y") } { |e| e.what.contains("whole passage") }
    // true
    // >>> SpellingPolicy.prCheckedIncremental(\flats, "x", "y")   -> flats
    //
    // ^ Symbol | Function | SpellingPolicy
    *prCheckedIncremental { |policy, label, doing|
        if (policy.isKindOf(SpellingPolicy) and: { policy.requiresWholePassage }) {
            Error(
                "%: % needs the whole passage, and % Spell with a scalar or "
                "local-context policy.".format(label, policy, doing)
            ).throw
        };
        ^policy
    }

    // ^ Self
    *prNew { |name, chooser, whole = false|
        ^super.new.prInit(name, chooser, whole)
    }

    // ^ Self
    prInit { |argName, argChooser, whole|
        name = argName;
        chooser = argChooser;
        requiresWholePassage = whole;
        ^this
    }

    // Answers a candidate Event or its pitch.
    //
    // ^ Event | MusicPitch
    value { |ctx| ^chooser.value(ctx) }

    // >>> SpellingPolicy.flats.asString   -> a SpellingPolicy(flats)
    printOn { |stream| stream << "a SpellingPolicy(" << name << ")" }

    // Built-ins name the Symbol they represent.
    //
    // >>> SpellingPolicy.flats.builtInSpelling                    -> flats
    // >>> SpellingPolicy.prefer([[\b, \flat]]).builtInSpelling.isNil   -> true
    //
    // ^ Self
    *prBuiltIn { |spelling|
        ^this.prNew(spelling,
            { |ctx| this.prSpellAt(spelling, ctx[\pitchNumber], ctx[\cents]) })
            .prSetBuiltIn(spelling)
    }

    // ^ Self
    prSetBuiltIn { |spelling| builtInSpelling = spelling; ^this }

    // Composed policies inherit whole-passage needs from their slots.
    //
    // >>> SpellingPolicy.prefer([[\b, \flat]]).requiresWholePassage   -> false
    // >>> SpellingPolicy.prefer([[\b, \flat]], SpellingPolicy.prWholePassage).requiresWholePassage
    // true
    //
    // ^ Boolean
    *prNeedsWholePassage { |slots|
        ^slots.any { |each|
            each.isKindOf(SpellingPolicy) and: { each.requiresWholePassage }
        }
    }

    // A nested slot is a Symbol, a Function or another policy.
    //
    // ^ MusicPitch
    *prSpell { |spelling, ctx|
        var answer;

        // `\written` reads `currentPitch`.
        if (spelling == \written) { ^ctx[\currentPitch] ?? { ctx[\minimal] } };
        if (spelling.isKindOf(Symbol)) {
            ^this.prSpellAt(spelling, ctx[\pitchNumber], ctx[\cents])
        };
        answer = spelling.value(ctx);
        ^if (answer.isKindOf(Event)) { answer[\pitch] } { answer }
    }

    // ^ MusicPitch
    *prSpellAt { |spelling, number, cents|
        ^MusicPitch.fromNumber(number, 0, spelling, cents ? 0)
    }

    // ^ Event
    *prFirstNamed { |wanted, ctx|
        wanted.do { |row|
            var found = ctx[\candidates].detect { |each|
                each[\noteName] == row[0] and: { each[\accidental] == row[1] }
            };
            if (found.notNil) { ^found }
        };
        ^nil
    }

    // ^ MusicPitch
    *prDistinctLine { |spelling, ctx|
        var here = this.prSpellAt(spelling, ctx[\pitchNumber], ctx[\cents]);
        var behind = ctx[\previousPitch];
        var ahead = ctx[\nextPitchNumber];
        var target;

        if (behind.isNil or: { ahead.isNil }) { ^here };
        if (this.prIsMicrotonal(here).not) { ^here };
        if (this.prSharesPosition(
            this.prSpellAt(spelling, behind.number, behind.cents), here).not) { ^here };
        if (this.prSharesPosition(
            this.prSpellAt(spelling, ahead, ctx[\nextCents]), here).not) { ^here };

        // A flatward inflection drops to the line below, a sharpward one rises.
        target = (here.step + this.prInflectionOf(here)) % MusicPitch.noteNames.size;
        ^ctx[\candidates].detect { |each| each[\pitch].step == target } ?? { here }
    }

    // One written staff position, which is a note name and an octave.
    // A note name alone would read an octave leap as a shared line.
    //
    // ^ Boolean
    *prSharesPosition { |one, other|
        ^one.step == other.step and: { one.octave == other.octave }
    }

    // The four quarter-tone rungs, the only accidentals this rule moves.
    //
    // ^ Boolean
    *prIsMicrotonal { |pitch|
        ^[\quarterFlat, \quarterSharp, \threeQuarterFlat, \threeQuarterSharp]
            .includes(pitch.accidental)
    }

    // ^ Integer
    *prInflectionOf { |pitch|
        ^if ([\quarterFlat, \threeQuarterFlat].includes(pitch.accidental)) { -1 } { 1 }
    }

    // ^ [(Symbol, Symbol)]
    *prCheckedRows { |rows|
        if (rows.isSequenceableCollection.not or: { rows.isKindOf(String) }) {
            Error(
                "SpellingPolicy.prefer: % is not a list of [noteName, accidental] rows."
                .format(rows.asCompileString)
            ).throw
        };
        if (rows.isEmpty) {
            Error("SpellingPolicy.prefer: empty table. Name at least one row.").throw
        };
        ^rows.collect { |row|
            if (row.isSequenceableCollection.not or: { row.size != 2 }) {
                Error(
                    "SpellingPolicy.prefer: % is not a [noteName, accidental] pair."
                    .format(row.asCompileString)
                ).throw
            };
            [
                this.prCheckedNoteName(row[0], "prefer"),
                this.prCheckedAccidental(row[1])
            ]
        }
    }

    // ^ Symbol
    *prCheckedNoteName { |noteName, label|
        var name = noteName.asSymbol;
        if (MusicPitch.noteNames.includes(name).not) {
            Error(
                "SpellingPolicy.%: % is not a note name. The names are %."
                .format(label, noteName.asCompileString, MusicPitch.noteNames)
            ).throw
        };
        ^name
    }

    // ^ Symbol
    *prCheckedAccidental { |accidental|
        var name = accidental.asSymbol;
        if (MusicPitch.accidentalNames.includes(name).not) {
            Error(
                "SpellingPolicy.prefer: % is not an accidental. The accidentals are %."
                .format(accidental.asCompileString, MusicPitch.accidentalNames)
            ).throw
        };
        ^name
    }

    // A nested slot takes anything the spelling entry points take.
    //
    // ^ Symbol | Function | SpellingPolicy
    *prCheckedSpelling { |spelling, what|
        if (spelling.isKindOf(Function) or: { spelling.isKindOf(SpellingPolicy) }) {
            ^spelling
        };
        ^this.prCheckedBuiltIn(spelling, what,
            "Use \\sharps, \\flats, \\minimal, \\written, a Function or a "
            "SpellingPolicy.")
    }

    // `base:` must spell neighbors without their contexts.
    //
    // ^ Symbol
    *prCheckedBase { |base|
        var spelling = if (base.isKindOf(SpellingPolicy)) { base.builtInSpelling } { base };

        if (spelling.isKindOf(Symbol).not) {
            Error(
                "SpellingPolicy.distinctLines: base % needs a context per neighbor, "
                "and this rule spells neighbors from their heights. Use \\minimal, "
                "\\sharps, \\flats, or the policy object for one of them."
                .format(base.asCompileString)
            ).throw
        };
        ^this.prCheckedBuiltIn(spelling, "distinctLines' base",
            "Use \\minimal, \\sharps, \\flats, or the policy object for one of them.",
            MusicPitch.contextFreeSpellingNames)
    }

    // Caller supplies the suggestion because spelling slots differ.
    //
    // ^ Symbol
    *prCheckedBuiltIn { |spelling, what, suggestion, admitted|
        if ((admitted ?? { MusicPitch.spellingNames }).includes(spelling).not) {
            Error(
                "SpellingPolicy: % is not a spelling for %. %"
                .format(spelling.asCompileString, what, suggestion)
            ).throw
        };
        ^spelling
    }
}
