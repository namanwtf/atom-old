package io.github.namanwtf.atom.agent.virtualdisplay

/** Used by callers under the same state lock; creation, removal, and child-process exit must all carry the identity of the corresponding instance. */
internal class VirtualDisplaySessionState {
    class Token internal constructor()

    data class Active(val token: Token, val displayId: Int)

    private var token: Token? = null
    var active: Active? = null
        private set

    fun begin(): Token {
        check(token == null)
        return Token().also { token = it }
    }

    fun activate(expected: Token, displayId: Int): Boolean {
        require(displayId > 0)
        if (token !== expected) return false
        active = Active(expected, displayId)
        return true
    }

    fun isCurrent(expected: Token): Boolean = token === expected

    fun invalidate(expected: Token? = token): Boolean {
        if (expected == null || token !== expected) return false
        token = null
        active = null
        return true
    }
}
