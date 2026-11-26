package services.matches.logic

import services.matches.objects.*

private[matches] object PlayerControl:
  def human(setup: MatchSetup, id: PlayerId): Either[ActionRejection, Player] =
    setup.players.find(_.id == id).toRight(ActionRejection.PlayerNotInMatch).flatMap { player =>
      Either.cond(player.kind == PlayerKind.Human, player, ActionRejection.PlayerIsComputer)
    }

  def switchTo(setup: MatchSetup, controls: List[ControlAssignment], player: PlayerId,
      target: AthleteId): Either[ActionRejection, List[ControlAssignment]] =
    for
      humanPlayer <- human(setup, player)
      current <- controls.find(_.player == player).toRight(ActionRejection.PlayerNotInMatch)
      _ <- Either.cond(setup.roster.exists(athlete => athlete.id == target && athlete.side == humanPlayer.side),
        (), ActionRejection.AthleteNotOnPlayersSide)
      occupied = controls.find(assignment => assignment.athlete == target && assignment.player != player)
      _ <- Either.cond(occupied.forall(entry =>
        setup.players.exists(candidate => candidate.id == entry.player && candidate.kind == PlayerKind.Computer)
      ), (), ActionRejection.AthleteControlledByAnotherPlayer)
    yield controls.map { entry =>
      if entry.player == player then entry.copy(athlete = target)
      else if occupied.contains(entry) then entry.copy(athlete = current.athlete)
      else entry
    }
