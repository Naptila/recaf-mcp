from __future__ import annotations

from pathlib import Path

import anyio
import pytest

from sessions import (
    PathReservationError,
    PathReservations,
    SessionCapacityError,
    SessionManager,
)


def test_pending_launches_count_toward_session_cap() -> None:
    async def scenario() -> None:
        manager = SessionManager(max_sessions=1)
        sid = await manager.reserve_slot()
        assert await manager.slot_count() == 1
        with pytest.raises(SessionCapacityError, match="maximum"):
            await manager.reserve_slot()

        await manager.abandon(sid)
        replacement = await manager.reserve_slot()
        assert replacement != sid
        await manager.abandon(replacement)
        assert await manager.slot_count() == 0

    anyio.run(scenario)


def test_path_reservations_reject_aliases_and_cross_session_targets(
    tmp_path: Path,
) -> None:
    input_one = tmp_path / "one.jar"
    input_two = tmp_path / "two.jar"
    input_one.write_bytes(b"one")
    input_two.write_bytes(b"two")
    alias = tmp_path / "input-alias.jar"
    alias.symlink_to(input_one)

    async def scenario() -> None:
        reservations = PathReservations()
        with pytest.raises(PathReservationError, match="aliases the input"):
            await reservations.reserve("alias", input_one, alias)

        shared_target = tmp_path / "shared.jar"
        first = await reservations.reserve("first", input_one, shared_target)
        assert first.input_path == input_one.resolve()
        assert first.autosave_path == shared_target.resolve()

        with pytest.raises(PathReservationError, match="another session"):
            await reservations.reserve("second", input_two, shared_target)
        with pytest.raises(PathReservationError, match="active session input"):
            await reservations.reserve("second", input_two, input_one)

        await reservations.release("first")
        second = await reservations.reserve("second", input_two, shared_target)
        assert second.autosave_path == shared_target.resolve()
        await reservations.release("second")

    anyio.run(scenario)


def test_relative_autosave_path_is_rejected(tmp_path: Path) -> None:
    input_path = tmp_path / "input.jar"
    input_path.write_bytes(b"jar")

    async def scenario() -> None:
        reservations = PathReservations()
        with pytest.raises(PathReservationError, match="absolute"):
            await reservations.reserve("sid", input_path, Path("relative.jar"))

    anyio.run(scenario)


def test_managed_autosave_can_be_disabled_for_unsavable_inputs(
    tmp_path: Path,
) -> None:
    input_path = tmp_path / "input.apk"
    input_path.write_bytes(b"apk")

    async def scenario() -> None:
        reservations = PathReservations()
        paths = await reservations.reserve("android", input_path)
        assert await reservations.owns("android", paths.autosave_path)

        await reservations.disable_autosave("android", paths.autosave_path)

        assert not await reservations.owns("android", paths.autosave_path)
        output = await reservations.reserve_output(
            "android",
            paths.autosave_path,
        )
        await reservations.release_output("android", output)
        await reservations.release("android")

    anyio.run(scenario)


def test_temporary_output_reservations_are_isolated_and_released(
    tmp_path: Path,
) -> None:
    input_one = tmp_path / "one.jar"
    input_two = tmp_path / "two.jar"
    input_one.write_bytes(b"one")
    input_two.write_bytes(b"two")
    temporary_output = tmp_path / "export.jar"

    async def scenario() -> None:
        reservations = PathReservations()
        first = await reservations.reserve("first", input_one)
        second = await reservations.reserve("second", input_two)

        with pytest.raises(PathReservationError, match="active session input"):
            await reservations.reserve_output("first", input_two)

        reserved = await reservations.reserve_output("first", temporary_output)
        assert reserved == temporary_output.resolve()
        with pytest.raises(PathReservationError, match="another session"):
            await reservations.reserve_output("second", temporary_output)

        await reservations.release_output("first", reserved)
        second_reserved = await reservations.reserve_output(
            "second",
            temporary_output,
        )
        assert second_reserved == reserved
        await reservations.release_output("second", second_reserved)

        await reservations.release_output("first", first.autosave_path)
        assert await reservations.owns("first", first.autosave_path)

        await reservations.release("first")
        await reservations.release("second")

    anyio.run(scenario)
