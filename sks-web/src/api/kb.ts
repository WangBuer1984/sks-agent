import { userClient } from './client';

/** KB 卡片列表项（对齐 Java CardSummary）。embedding 不返回（太大）。 */
export interface CardSummary {
  id: number;
  layer: string;
  cardType: string;
  title: string;
  content: string;
  updatedAt: string;
}

export type CardLayer = 'A' | 'B' | 'C';

/** 列出当前用户的未删卡片（可选 layer=A/B/C 过滤）。 */
export function listCards(layer?: CardLayer): Promise<CardSummary[]> {
  return userClient.get<CardSummary[], CardSummary[]>('/kb/cards', {
    params: layer ? { layer } : {},
  });
}

/** 新建卡片。content 为 JSON 文本（存 JSONB）。返回新 id。 */
export function createCard(
  layer: CardLayer,
  cardType: string,
  title: string,
  content: string,
): Promise<number> {
  return userClient.post<number, number>('/kb/cards', { layer, cardType, title, content });
}

/** 编辑卡片（改 title + content）。B 层会重算向量 + 归档旧值。 */
export function updateCard(id: number, title: string, content: string): Promise<void> {
  return userClient.put<void, void>(`/kb/cards/${id}`, { title, content });
}

/** 删除卡片。有引用且非 force 时后端返回 code=4006（BizError.message 含引用数）。 */
export function deleteCard(id: number, force = false): Promise<void> {
  return userClient.delete<void, void>(`/kb/cards/${id}`, { params: { force } });
}
