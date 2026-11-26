package services.matches.logic

import services.matches.objects.*
import FlightRules.clamp

private[logic] object TeamSpacing:
  private val avoidWithin = 3.6
  private val contactClearance = 1.65
  private val lookAheadSeconds = .25

  def steer(athlete: AthleteId, flight: FlightState, teammates: List[(AthleteId, FlightState)],
      input: FlightInput): FlightInput =
    val location = flight.position.value
    val neighbors = teammates.map { (id, mate) =>
      val offset = location - mate.position.value
      val relativeVelocity = flight.velocity.value - mate.velocity.value
      val speedSquared = relativeVelocity.dot(relativeVelocity)
      val closestAt = clamp(-offset.dot(relativeVelocity) / math.max(speedSquared, 1e-9), 0, lookAheadSeconds)
      (id, offset, (offset + relativeVelocity * closestAt).length)
    }
    neighbors.minByOption { case (id, _, distance) => (distance, id.value) } match
      case Some((id, offset, distance)) if distance < avoidWithin =>
        val away = Direction.from(offset).map(_.value).getOrElse(
          Vec3(0, if athlete.value < id.value then -1 else 1, 0))
        val wanted = Vec3(input.forward.value, input.strafe.value, input.climb.value)
        val weight = clamp((avoidWithin - distance) / (avoidWithin - contactClearance), 0, 1)
        val sidestep = if wanted.dot(away) < 0 then Vec3(-away.y, away.x, 0) * .65 else Vec3.zero
        val adjusted = wanted * (1 - weight) + (away + sidestep) * weight
        def axis(value: Double): AxisInput = AxisInput(clamp(value, -1, 1))
        input.copy(forward = axis(adjusted.x), strafe = axis(adjusted.y), climb = axis(adjusted.z))
      case _ => input
