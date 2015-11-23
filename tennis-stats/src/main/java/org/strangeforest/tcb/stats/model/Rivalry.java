package org.strangeforest.tcb.stats.model;

public class Rivalry {

	private final RivalryPlayer player1, player2;
	private WonLost wonLost;
	private final LastMatch lastMatch;

	public Rivalry(RivalryPlayer player1, RivalryPlayer player2, WonLost wonLost, LastMatch lastMatch) {
		this.player1 = player1;
		this.player2 = player2;
		this.wonLost = wonLost;
		this.lastMatch = lastMatch;
	}

	public RivalryPlayer getPlayer1() {
		return player1;
	}

	public RivalryPlayer getPlayer2() {
		return player2;
	}

	public WonLost getWonLost() {
		return wonLost;
	}

	public void addWonLost(WonLost wonLost) {
		this.wonLost = this.wonLost.add(wonLost);
	}

	public int getMatches() {
		return wonLost.getTotal();
	}

	public int getWon() {
		return wonLost.getWon();
	}

	public int getLost() {
		return wonLost.getLost();
	}

	public LastMatch getLastMatch() {
		return lastMatch;
	}
}
