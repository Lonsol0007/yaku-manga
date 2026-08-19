package yaku.ui.reader.viewer.navigation

import yaku.ui.reader.viewer.ViewerNavigation

/**
 * Visualization of default state without any inversion
 * +---+---+---+
 * | M | M | M |   P: Previous
 * +---+---+---+
 * | M | M | M |   M: Menu
 * +---+---+---+
 * | M | M | M |   N: Next
 * +---+---+---+
*/
class DisabledNavigation : ViewerNavigation() {

    override var regionList: List<Region> = emptyList()
}
