    // @rest-endpoint {{HttpMethod}} {{EndpointPath}} -> {{TargetServiceToken}}.{{OperationName}}
    @{{HttpMethod}}
    @Produces(MediaType.APPLICATION_JSON)
    public Response {{JavaMethodName}}(@Context final UriInfo uri{{BodyParameter}}) {
        return RestEndpointSupport.call(uri, {{BodyArgument}}, {{RequestClassName}}.create(),
                request -> {{WebPackageName}}.rest.{{ClientClassName}}.get().{{OperationMethodName}}(request));
    }
    // @rest-endpoint-end
