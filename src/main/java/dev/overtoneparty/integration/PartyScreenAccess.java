package dev.overtoneparty.integration;

/**
 * Доступ к геометрии списка экрана Overtone из действий кнопок.
 *
 * <p>Кнопки строк создаются один раз при сборке экрана, а прокрутка меняет
 * {@code page} без пересборки. Поэтому действие не хранит сущность заранее, а
 * читает актуальные {@code page} и {@code rows} в момент нажатия.
 */
public interface PartyScreenAccess {
    /** Текущая страница списка Overtone. */
    int overtoneParty$page();

    /** Сколько строк помещается в списке. */
    int overtoneParty$rows();

    /** Список лайков сдвинут на одну строку под кнопку «все лайкнутые». */
    boolean overtoneParty$likedShift();

    /** Верхняя граница списка в координатах окна. */
    int overtoneParty$listTop();

    /** Текущая позиция мыши в координатах окна. */
    int overtoneParty$mouseY();
}
