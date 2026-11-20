package io.github.chenforegone.encoreticket.model.vo;

import java.util.List;

/**
 * 影厅瞬时操作资格，写入仍需锁后重查。
 * @author c1342
 */
public record HallActionsVO(boolean canRename, boolean canEnable, boolean canDisable, boolean canViewSeats,
        boolean canAddSeats, List<String> addSeatBlockReasons) {
    public HallActionsVO { addSeatBlockReasons = List.copyOf(addSeatBlockReasons); }
}
