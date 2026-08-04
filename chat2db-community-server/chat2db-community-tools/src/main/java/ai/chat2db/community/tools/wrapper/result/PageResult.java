package ai.chat2db.community.tools.wrapper.result;
import ai.chat2db.community.tools.wrapper.IResult;
import ai.chat2db.community.tools.wrapper.param.PageQueryParam;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult.Page;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@AllArgsConstructor
public class PageResult<T> implements IResult<List<T>> {


    private Boolean success;


    private String errorCode;


    private String errorMessage;


    private List<T> data;


    private Integer pageNo;


    private Integer pageSize;


    private Long total;


    private String traceId;


    private Boolean hasNextPage;


    private String errorDetail;


    private String solutionLink;

    public PageResult() {
        this.pageNo = 1;
        this.pageSize = 10;
        this.total = 0L;
        this.success = Boolean.TRUE;
    }

    private PageResult(List<T> data, Long total, Long pageNo, Long pageSize) {
        this();
        this.data = data;
        this.total = total;
        if (pageNo != null) {
            this.pageNo = Math.toIntExact(pageNo);
        }
        if (pageSize != null) {
            this.pageSize = Math.toIntExact(pageSize);
        }
    }

    private PageResult(List<T> data, Long total, Integer pageNo, Integer pageSize) {
        this();
        this.data = data;
        this.total = total;
        if (pageNo != null) {
            this.pageNo = pageNo;
        }
        if (pageSize != null) {
            this.pageSize = pageSize;
        }
    }


    public static <T> PageResult<T> of(List<T> data, Long total, Long pageNo, Long pageSize) {
        return new PageResult<>(data, total, pageNo, pageSize);
    }


    public static <T> PageResult<T> of(List<T> data, Long total, Integer pageNo, Integer pageSize) {
        return new PageResult<>(data, total, pageNo, pageSize);
    }


    public static <T> PageResult<T> of(List<T> data, Long total, PageQueryParam param) {
        return new PageResult<>(data, total, param.getPageNo(), param.getPageSize());
    }


    public static <T> PageResult<T> of(List<T> data, PageQueryParam param) {
        return new PageResult<>(data, 0L, param.getPageNo(), param.getPageSize());
    }


    public static <T> PageResult<T> empty(Long pageNo, Long pageSize) {
        return of(Collections.emptyList(), 0L, pageNo, pageSize);
    }


    public static <T> PageResult<T> empty(Integer pageNo, Integer pageSize) {
        return of(Collections.emptyList(), 0L, pageNo, pageSize);
    }


    public Boolean calculateHasNextPage() {
        if (total > 0) {
            return (long)pageSize * pageNo < total;
        }
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.size() >= pageSize;
    }


    @Deprecated
    public boolean hasNextPage() {
        return getHasNextPage();
    }

    public Boolean getHasNextPage() {
        if (hasNextPage == null) {
            hasNextPage = calculateHasNextPage();
        }
        return hasNextPage;
    }


    public boolean hasData() {
        return hasData(this);
    }


    public static <T> PageResult<T> error(String errorCode, String errorMessage) {
        PageResult<T> result = new PageResult<>();
        result.errorCode = errorCode;
        result.errorMessage = errorMessage;
        result.success = Boolean.FALSE;
        return result;
    }


    public static boolean hasData(PageResult<?> pageResult) {
        return pageResult != null && pageResult.getSuccess() && pageResult.getData() != null && !pageResult.getData()
            .isEmpty();
    }


    public <R> PageResult<R> map(Function<T, R> mapper) {
        List<R> returnData = hasData(this) ? getData().stream().map(mapper).collect(Collectors.toList())
            : Collections.emptyList();
        PageResult<R> pageResult = new PageResult<>();
        pageResult.setSuccess(getSuccess());
        pageResult.setErrorCode(getErrorCode());
        pageResult.setErrorMessage(getErrorMessage());
        pageResult.setData(returnData);
        pageResult.setPageNo(getPageNo());
        pageResult.setPageSize(getPageSize());
        pageResult.setTotal(getTotal());
        pageResult.setTraceId(getTraceId());
        return pageResult;
    }


    public <R> ListResult<R> mapToList(Function<T, R> mapper) {
        List<R> returnData = hasData(this) ? getData().stream().map(mapper).collect(Collectors.toList())
            : Collections.emptyList();
        ListResult<R> result = new ListResult<>();
        result.setSuccess(getSuccess());
        result.setErrorCode(getErrorCode());
        result.setErrorMessage(getErrorMessage());
        result.setTraceId(getTraceId());
        result.setData(returnData);
        return result;
    }


    public <R> WebPageResult<R> mapToWeb(Function<T, R> mapper) {
        List<R> returnData = hasData(this) ? getData().stream().map(mapper).collect(Collectors.toList())
            : Collections.emptyList();
        WebPageResult<R> pageResult = new WebPageResult<>();
        pageResult.setSuccess(getSuccess());
        pageResult.setErrorCode(getErrorCode());
        pageResult.setErrorMessage(getErrorMessage());
        pageResult.setTraceId(getTraceId());
        Page<R> page = new Page<>();
        pageResult.setData(page);
        page.setData(returnData);
        page.setPageNo(getPageNo());
        page.setPageSize(getPageSize());
        page.setTotal(getTotal());
        pageResult.setData(page);
        return pageResult;
    }

    @Override
    public boolean success() {
        return success;
    }

    @Override
    public void success(boolean success) {
        this.success = success;
    }

    @Override
    public String errorCode() {
        return errorCode;
    }

    @Override
    public void errorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    @Override
    public String errorMessage() {
        return errorMessage;
    }

    @Override
    public void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    public void errorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    @Override
    public String errorDetail() {
        return errorDetail;
    }

    @Override
    public void solutionLink(String solutionLink) {
        this.solutionLink = solutionLink;
    }

    @Override
    public String solutionLink() {
        return solutionLink;
    }
}
