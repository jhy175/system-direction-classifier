package cn.edu.whu.irlab.systemdirectionclassifier.api.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ylzl.eden.spring.framework.dto.Result;

import java.util.Collection;
import java.util.Collections;

/**
 * 列表响应。Eden 2.4.x 仅提供单条和分页响应，由应用定义列表响应。
 *
 * @param <T> 数据类型
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MultiResult<T> extends Result {

	private Collection<T> data;

	public Collection<T> getData() {
		return data == null ? Collections.emptyList() : data;
	}

	public static <T> MultiResult<T> build(Collection<T> data) {
		MultiResult<T> result = new MultiResult<>();
		result.setSuccess(true);
		result.setData(data);
		return result;
	}
}
