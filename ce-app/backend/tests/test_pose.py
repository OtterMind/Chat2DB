"""Pose bridge: the geometry is testable without MediaPipe; the bridge degrades."""
from __future__ import annotations

from core.engine import pose


class Point:
    def __init__(self, x, y, visibility=1.0):
        self.x, self.y, self.visibility = x, y, visibility


def _landmarks(points):
    class L:
        landmark = points
    return L().landmark


def test_the_torso_centre_is_the_average_of_visible_joints():
    points = [Point(0, 0, 0)] * 25
    points[11] = Point(0.4, 0.3)   # left shoulder
    points[12] = Point(0.6, 0.3)   # right shoulder
    points[23] = Point(0.4, 0.7)   # left hip
    points[24] = Point(0.6, 0.7)   # right hip

    centre = pose.centre_from_landmarks(_landmarks(points))

    assert centre is not None
    assert abs(centre[0] - 0.5) < 1e-9
    assert abs(centre[1] - 0.5) < 1e-9


def test_invisible_joints_do_not_vote():
    points = [Point(0, 0, 0)] * 25
    points[11] = Point(0.4, 0.3)
    points[12] = Point(0.6, 0.3, visibility=0.1)  # below the floor
    points[23] = Point(0.4, 0.7)

    centre = pose.centre_from_landmarks(_landmarks(points))

    assert centre is not None
    assert abs(centre[0] - 0.4) < 1e-9  # only the two visible joints vote


def test_one_visible_joint_is_not_a_person():
    points = [Point(0, 0, 0)] * 25
    points[23] = Point(0.5, 0.5)

    assert pose.centre_from_landmarks(_landmarks(points)) is None


def test_without_the_engine_the_bridge_hands_back_none():
    if pose.available():
        return  # on a fetched machine this is not the case under test

    assert pose.track_frame(object()) is None
