package ai.chat2db.community.tools.wrapper.result.web;
import ai.chat2db.community.tools.wrapper.IResult;
import ai.chat2db.community.tools.wrapper.param.PageQueryParam;
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
public class WebPageResult<T> implements IResult<List<T>> {


    private Boolean success;


    private String errorCode;


    private String errorMessage;


    private Page<T> data;


    private String traceId;


    private String errorDetail;


    private String solutionLink;

    public WebPageResult() {
        this.success = Boolean.TRUE;
        this.data = new Page<>();
    }

    private WebPageResult(List<T> data, Long total, Long pageNo, Long pageSize) {
        this.success = Boolean.TRUE;
        this.data = new Page<>(data, total, pageNo, pageSize);
    }

    private WebPageResult(List<T> data, Long total, Integer pageNo, Integer pageSize) {
        this.success = Boolean.TRUE;
        this.data = new Page<>(data, total, pageNo, pageSize);
    }


    public static <T> WebPageResult<T> of(List<T> data, Long total, Long pageNo, Long pageSize) {
        return new WebPageResult<>(data, total, pageNo, pageSize);
    }


    public static <T> WebPageResult<T> of(List<T> data, Long total, Integer pageNo, Integer pageSize) {
        return new WebPageResult<>(data, total, pageNo, pageSize);
    }


    public static <T> WebPageResult<T> of(List<T> data, Long total, PageQueryParam param) {
        return new WebPageResult<>(data, total, param.getPageNo(), param.getPageSize());
    }


    public static <T> WebPageResult<T> empty(Long pageNo, Long pageSize) {
        return of(Collections.emptyList(), 0L, pageNo, pageSize);
    }


    public static <T> WebPageResult<T> empty(Integer pageNo, Integer pageSize) {
        return of(Collections.emptyList(), 0L, pageNo, pageSize);
    }


    @Deprecated
    public boolean hasNextPage() {
        return getHasNextPage();
    }

    public Boolean getHasNextPage() {
        if (data == null) {
            return Boolean.FALSE;
        }
        return data.getHasNextPage();
    }


    public static <T> WebPageResult<T> error(String errorCode, String errorMessage) {
        WebPageResult<T> result = new WebPageResult<>();
        result.errorCode = errorCode;
        result.errorMessage = errorMessage;
        result.success = Boolean.FALSE;
        return result;
    }


    public static boolean hasData(WebPageResult<?> pageResult) {
        return pageResult != null && pageResult.getSuccess() && pageResult.getData() != null
            && pageResult.getData().getData() != null && !pageResult.getData().getData().isEmpty();
    }


    public <R> WebPageResult<R> map(Function<T, R> mapper) {
        List<R> returnData = hasData(this) ? getData().getData().stream().map(mapper).collect(Collectors.toList())
            : Collections.emptyList();
        WebPageResult<R> pageResult = new WebPageResult<>();
        pageResult.setSuccess(getSuccess());
        pageResult.setErrorCode(getErrorCode());
        pageResult.setErrorMessage(getErrorMessage());
        pageResult.setTraceId(getTraceId());
        Page<R> page = new Page<>();
        pageResult.setData(page);
        page.setData(returnData);
        page.setPageNo(data.getPageNo());
        page.setPageSize(data.getPageSize());
        page.setTotal(data.getTotal());
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


    @Data
    public static class Page<T> {


        private List<T> data;


        private Integer pageNo;


        private Integer pageSize;


        private Long total;


        private Boolean hasNextPage;

        public Page() {
            this.pageNo = 1;
            this.pageSize = 10;
            this.total = 0L;
        }

        private Page(List<T> data, Long total, Long pageNo, Long pageSize) {
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

        private Page(List<T> data, Long total, Integer pageNo, Integer pageSize) {
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

        public Boolean getHasNextPage() {
            if (hasNextPage == null) {
                hasNextPage = calculateHasNextPage();
            }
            return hasNextPage;
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
    }
}
