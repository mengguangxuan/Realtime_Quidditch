class_name MatchApi
extends Node

signal snapshot_received(frame: Dictionary)
signal connection_changed(connected: bool)

var base_url := "http://127.0.0.1:8080/matches/demo"
var _poll := HTTPRequest.new()
var _send := HTTPRequest.new()
var _poll_time := 0.0
var _time_since_flight := 0.0
var _outgoing: Array[Dictionary] = []
var _wanted_flight: Dictionary = {}
var _last_flight: Dictionary = {}
var _requesting_start := false
var _in_play := false


func _ready() -> void:
	var origin := OS.get_environment("QUIDDITCH_BACKEND_URL")
	if not origin.is_empty():
		base_url = origin.trim_suffix("/") + "/matches/demo"
	add_child(_poll)
	add_child(_send)
	_poll.timeout = 1.0
	_send.timeout = 1.0
	_poll.request_completed.connect(_poll_completed)
	_send.request_completed.connect(_command_completed)


func _process(delta: float) -> void:
	_poll_time -= delta
	_time_since_flight += delta
	if _poll_time <= 0.0 and _poll.get_http_client_status() == HTTPClient.STATUS_DISCONNECTED:
		_poll_time = 0.055
		_poll.request(base_url)
	if _send.get_http_client_status() != HTTPClient.STATUS_DISCONNECTED:
		return
	if _requesting_start:
		_requesting_start = false
		_send.request(base_url + "/start", [], HTTPClient.METHOD_POST)
	elif _in_play:
		if not _outgoing.is_empty():
			_send_control(_outgoing.pop_front())
		elif not _wanted_flight.is_empty() and (_wanted_flight != _last_flight or _time_since_flight >= 0.25):
			_last_flight = _wanted_flight
			_time_since_flight = 0.0
			_send_control({"Fly": {"input": _wanted_flight}})


func start_match() -> void:
	_requesting_start = true


func flight(input: Dictionary) -> void:
	_wanted_flight = input


func control(action: Dictionary) -> void:
	if _in_play and _outgoing.size() < 12:
		_outgoing.append(action)


func _send_control(action: Dictionary) -> void:
	_send.request(
		base_url + "/controls", ["Content-Type: application/json"], HTTPClient.METHOD_POST,
		JSON.stringify({"player": "home", "control": action})
	)


func _poll_completed(result: int, code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	var decoded: Variant = JSON.parse_string(body.get_string_from_utf8())
	if result != HTTPRequest.RESULT_SUCCESS or code != 200 or not decoded is Dictionary:
		_in_play = false
		_outgoing.clear()
		_last_flight = {}
		connection_changed.emit(false)
		return
	var now_live: bool = decoded["state"]["phase"].has("InPlay")
	if not now_live:
		_outgoing.clear()
		_last_flight = {}
	_in_play = now_live
	connection_changed.emit(true)
	snapshot_received.emit(decoded)


func _command_completed(result: int, code: int, _headers: PackedStringArray, _body: PackedByteArray) -> void:
	if result != HTTPRequest.RESULT_SUCCESS or (code != 202 and code != 200):
		_last_flight = {}
