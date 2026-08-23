# ScoreJSON interchange

ScoreJSON is the shared, versioned wire format for Rastrum and other
projects, like metasonic-score, an unreleased project also working
with music notation model and music composition work.

Projects exchange whole scores without adopting each other's model.

[Back to index](index.md).

## Interchange with metasonic-score

`ScoreJSONWriter` and `ScoreJSONReader` are the two ends of that
contract: a tagged tree with a `"type"` discriminator, exact rationals
as `[numerator, denominator]` pairs, and no floats on the wire except
`cents`, which is a deviation rather than a duration.

```json
{"format":"score-json","version":31,"type":"score","title":"Study I",
 "staves":[{"type":"staff","name":"Violin","clef":"treble","measures":[
   {"type":"measure","meter":[4,4],"elements":[
     {"type":"tuplet","multiplier":[2,3],"elements":[
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2],"tiesToNext":true},
       {"type":"note","pitch":{"step":0,"alter":[0,1],"octave":4,"cents":0.0},
        "duration":[1,2]}]}]}]}]}
```

A tie needs somewhere to land, so the example carries both halves: the
first note ties forward and the second continues the same pitch. A tie
with no compatible leaf after it is a dangling tie, and
`MusicXMLWriter` throws on one rather than opening a tie it never
closes.

### Current Format, Version 31

The current document version.

#### Document rules

- A document is one JSON object for a whole score. Fragments are not
  documents.
- The envelope is required: `"format":"score-json"`, `"version":31`,
  and `"type":"score"`.
- Every tree node carries a `"type"` discriminator.
- Readers accept exactly one version. Older and newer versions are
  refused at the envelope.
- Required fields must be present. Unknown fields and fields on the
  wrong node are refused.
- Writers omit absent, empty and default facts rather than writing `null`.
- Array order is semantic: staves, measures, elements, markings, spanners,
  directions, pitches and graces read in the order written.
- Durations, offsets, tuplets and alterations use exact rational pairs. The only
  float field is `cents`.

#### Scalar encodings

| Name             | JSON shape                | Contract                                           |
| ---------------- | ------------------------- | -------------------------------------------------- |
| rational         | `[numerator,denominator]` | both whole numbers; denominator > 0                |
| integer          | JSON number               | whole number; no decimal spelling                  |
| positive integer | JSON number               | integer > 0                                        |
| string           | JSON string               | escaped JSON text; no raw control characters       |
| boolean          | `true` or `false`         | no alternate spellings                             |
| duration         | rational                  | written time; ordinary leaves may need preparation |
| grace duration   | rational                  | must be one notatable note value                   |
| pitch            | object                    | `step`, `alter`, `octave`, optional `cents`        |

A pitch is:

| Field    | Contract                                                 |
| -------- | -------------------------------------------------------- |
| `step`   | integer 0..6, for C D E F G A B                          |
| `alter`  | rational semitone offset on the quarter-tone grid, -2..2 |
| `octave` | integer, scientific pitch notation                       |
| `cents`  | plain decimal number, absent means `0`; writer emits it  |

#### Tree nodes

Each row requires its own `"type"` value.

| Type        | Required fields               | Optional fields                                                      |
| ----------- | ----------------------------- | -------------------------------------------------------------------- |
| `score`     | `format`, `version`, `staves` | `title`, `composer`                                                  |
| `staff`     | `measures`                    | `name`, `shortName`, `clef`                                          |
| `measure`   | `meter`, `elements`           | `meterGrouping`, `clef`, `directions`, `barDuration`, `metricOffset` |
| `voice`     | `elements`                    | `name`                                                               |
| `container` | `elements`                    |                                                                      |
| `tuplet`    | `multiplier`, `elements`      | `actualNotes`, `normalNotes`                                         |
| `note`      | `pitch`, `duration`           | `tiesToNext`, `markings`, `spanners`, `graces`, `graceStyle`         |
| `rest`      | `duration`                    | `markings`, `spanners`, `graces`, `graceStyle`                       |
| `chord`     | `pitches`, `duration`         | `tiesToNext`, `markings`, `spanners`, `graces`, `graceStyle`         |

Child fields are typed by position: `score.staves` holds `staff`
nodes, `staff.measures` holds `measure` nodes, and every `elements`
array holds score elements: `voice`, `container`, `tuplet`, `note`,
`rest`, or `chord`. `graces` is separate and holds only `graceNote`
and `graceChord`.

#### Node fields

`meter` is `[count,unit]`, with both values positive integers.
`meterGrouping` is a non-empty list of positive integers whose sum is
`count`. When it is absent, the meter is undivided.

`clef` is one of `alto`, `bass`, `percussion`, `tenor`, or `treble`. On a staff
it is the opening clef. On a measure it is a clef change at that bar.

`name` and `shortName` are the staff's printed names, the full one
beside the first system and the abbreviation beside every system after
it. Both are prose and both are optional. `shortName` is never an
empty string: a staff with no abbreviation omits the field, and both
sides refuse one that says nothing. `name` carries no such check.

`barDuration` and `metricOffset` are rational pairs and must appear
together or not at all. Together they define a partial bar: the span
and where that span begins inside the notional meter.

`multiplier` is the tuplet time multiplier as a rational.
`actualNotes` and `normalNotes` must appear together or not at all.
When present they are positive integers and must reduce to the
multiplier beside them.

`tiesToNext` is omitted when false. On a note it is a Boolean. On a
chord it is one Boolean per pitch. A whole-chord tie is an all-true
mask, not `true`.

#### Records

| Record       | Required fields               | Optional fields                                     |
| ------------ | ----------------------------- | --------------------------------------------------- |
| `pitch`      | `step`, `alter`, `octave`     | `cents`                                             |
| `marking`    | `type`, `value`               | `placement`                                         |
| `spanner`    | `type`, `edge`, `id`          | `direction`, `text`, `placement`                    |
| `direction`  | `type`                        | `text`, `offset`, `unit`, `perMinute`, `edge`, `id` |
| `graceNote`  | `type`, `pitch`, `duration`   |                                                     |
| `graceChord` | `type`, `pitches`, `duration` |                                                     |

Marking types are `dynamic`, `sforzando`, `articulation`, `technical`,
and `text`. Dynamic values are `ppppp`, `pppp`, `ppp`, `pp`, `p`,
`mp`, `mf`, `f`, `ff`, `fff`, `ffff`, `fffff`. A `technical` marking
says how the sound is produced rather than how the attack is shaped,
and its values are `upbow`, `downbow`, `stopped`, `snapPizzicato`,
`openString` and `harmonic`. A kind of its own rather than more
articulation vocabulary, which is the split MusicXML draws too between
`<technical>` and `<articulations>`. The kind is broad and these
bowed-string values are only the first admitted to it.

A `sforzando`'s value is the level its attack lands on, `mp`, `mf`,
`f` or `ff`, and a leaf carrying one beside a dynamic is the compound
a score prints as one glyph. Articulation values are `staccato`,
`staccatissimo`, `tenuto`, `accent`, `marcato`, `portato`, `fermata`,
`breath`, and `caesura`. A text marking's `value` is prose and its
`placement` is required. Non-text markings refuse `placement`.
Placements are `above` and `below`.

Spanner types are `slur`, `hairpin`, `text`, `beam`, and `glissando`.
Edges are `start` and `stop`. `id` is a positive integer. A hairpin
start requires `direction`, `crescendo` or `diminuendo`. A text start
requires `text` and `placement`. Stops, slurs, beams and glissandi
carry no prose or direction. A glissando pair is adjacent: its two
ends sit on attacks that follow each other, and both hold the same
number of noteheads.

Direction types are `tempo`, `rehearsalMark`, `text`, and `tempoRamp`.
`offset` is a rational into the bar and defaults to zero. A direction
needs `text`, except a tempo may instead carry a metronome mark. A
metronome mark is `unit` plus `perMinute`, both or neither. `unit` is
one notatable note value and `perMinute` is a positive integer.

`tempoRamp` is the one paired direction: a gradual tempo change is two
endpoints, each local to its own bar, because a span crossing a
barline cannot be one direction whose offset must fall inside its bar.
So `edge` and `id` are **required on `tempoRamp` and refused on every
other kind** — the same shape a `spanner` record uses. `edge` is
`start` or `stop`. `id` is a positive integer pairing the two ends.

A `tempoRamp` start carries the prose and, where the ramp says where
it is heading, a metronome mark as its target. A stop carries neither:
it repeats nothing its start said. It is the one direction record that
may say nothing but its `type`, `edge` and `id`, and the "a direction
needs `text`" rule is relaxed for exactly that case and no other. An
ordinary direction saying neither prose nor a mark is still refused.

```json
{"type":"tempoRamp","edge":"start","id":1,"text":"rit.","unit":[1,4],"perMinute":60}
{"type":"tempoRamp","edge":"stop","id":1}
```


Grace groups sit on a leaf. `graces` must be a non-empty list when
present. `graceStyle` is `grace` or `acciaccatura`. When it is absent,
the style is `grace`. A grace leaf is only `graceNote` or
`graceChord`, with pitch data and a grace duration. It carries no
ties, markings, spanners, or nested grace group.

#### Validation boundary

`ScoreJSONReader` checks the document shape and rebuilds a tree. It
does not decide whether the score is complete notation.
`Rastrum.readJSON` also runs `Validator`, so it catches score facts
such as a short undeclared bar, a dangling tie, an unpaired spanner,
or a direction outside its bar.

`Rastrum.writeJSON` does not add automatic beams. With its default
`prepare:true`, it writes the prepared tree. With `prepare:false`, it
writes the given tree. A beam in the document is a beam the score
already carried.

## MusicXML divisions

MusicXML counts time in divisions per quarter note, so every duration
has to be a whole number of them. A fixed value cannot do that: 768
has no factor of five, so each fifth of a 4/4 bar rounds to 614 and
the five sum to 3070 against a bar of 3072: exact in the model, two
ticks short on the wire.

`MusicXMLWriter` computes divisions per score instead, as the least
common multiple of what every leaf needs and a base of 768. Ordinary
binary music still says 768, a quintuplet score says 3840, and the
arithmetic is exact all the way to the integer with no rounding step.
Past `maxDivisions` it throws rather than emit a technically legal
value that importers mishandle.
