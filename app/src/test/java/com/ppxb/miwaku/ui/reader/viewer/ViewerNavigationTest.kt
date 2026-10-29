package com.ppxb.miwaku.ui.reader.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerNavigationTest {

    @Test
    fun startThirdTurnsBack() {
        assertEquals(NavigationAction.Previous, ViewerNavigation.actionFor(ViewerNavigation.zoneOf(0f, 900f)))
        assertEquals(NavigationAction.Previous, ViewerNavigation.actionFor(ViewerNavigation.zoneOf(299f, 900f)))
    }

    @Test
    fun middleThirdOpensTheMenu() {
        assertEquals(NavigationAction.Menu, ViewerNavigation.actionFor(ViewerNavigation.zoneOf(450f, 900f)))
    }

    @Test
    fun endThirdTurnsForward() {
        assertEquals(NavigationAction.Next, ViewerNavigation.actionFor(ViewerNavigation.zoneOf(601f, 900f)))
        assertEquals(NavigationAction.Next, ViewerNavigation.actionFor(ViewerNavigation.zoneOf(900f, 900f)))
    }

    @Test
    fun tapsOutsideTheBoundsAreClamped() {
        assertEquals(TapZone.Start, ViewerNavigation.zoneOf(-50f, 900f))
        assertEquals(TapZone.End, ViewerNavigation.zoneOf(2000f, 900f))
    }

    @Test
    fun unknownWidthFallsBackToTheMenu() {
        assertEquals(TapZone.Center, ViewerNavigation.zoneOf(10f, 0f))
    }
}
