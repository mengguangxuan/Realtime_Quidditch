package services.matches.objects

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class PlayTick(value: Long)

object PlayTick:
  given Codec.AsObject[PlayTick] = deriveCodec[PlayTick]

final case class MatchClock(elapsed: PlayTick, durationTicks: Long)

object MatchClock:
  val ticksPerSecond = 20

  given Codec.AsObject[MatchClock] = deriveCodec[MatchClock]

  final case class Attack(side: Side, remainingTicks: Long)

  object Attack:
    given Codec.AsObject[Attack] = deriveCodec[Attack]
