package com.kicknext.pos_printers

import net.posprinter.esc.PosUdpNet
import net.posprinter.posprinterface.UdpCallback


class ExtendPosUdpNet : PosUdpNet() {
    var isSearch = false
        private set

    override fun searchNetDevice(callback: UdpCallback?) {
        isSearch = true
        super.searchNetDevice(callback)
    }

    override fun closeNetSocket() {
        println("closeNetSocket called")
        super.closeNetSocket()
        isSearch = false
    }
}